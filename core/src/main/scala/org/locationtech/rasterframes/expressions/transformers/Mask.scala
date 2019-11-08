/*
 * This software is licensed under the Apache 2 license, quoted below.
 *
 * Copyright 2019 Astraea, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     [http://www.apache.org/licenses/LICENSE-2.0]
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.locationtech.rasterframes.expressions.transformers

import com.typesafe.scalalogging.Logger
import geotrellis.raster
import geotrellis.raster.Tile
import geotrellis.raster.mapalgebra.local.{Defined, InverseMask ⇒ gtInverseMask, Mask ⇒ gtMask}
import org.apache.spark.sql.catalyst.analysis.TypeCheckResult
import org.apache.spark.sql.catalyst.analysis.TypeCheckResult.{TypeCheckFailure, TypeCheckSuccess}
import org.apache.spark.sql.catalyst.expressions.codegen.CodegenFallback
import org.apache.spark.sql.catalyst.expressions.{Expression, ExpressionDescription, Literal, TernaryExpression}
import org.apache.spark.sql.functions.lit
import org.apache.spark.sql.rf.TileUDT
import org.apache.spark.sql.types.DataType
import org.apache.spark.sql.{Column, TypedColumn}
import org.locationtech.rasterframes.encoders.CatalystSerializer._
import org.locationtech.rasterframes.expressions.DynamicExtractors._
import org.locationtech.rasterframes.expressions.localops.IsIn
import org.locationtech.rasterframes.expressions.row
import org.slf4j.LoggerFactory

abstract class Mask(val left: Expression, val middle: Expression, val right: Expression, inverse: Boolean)
  extends TernaryExpression with CodegenFallback with Serializable {

  @transient protected lazy val logger = Logger(LoggerFactory.getLogger(getClass.getName))


  override def children: Seq[Expression] = Seq(left, middle, right)

  override def checkInputDataTypes(): TypeCheckResult = {
    if (!tileExtractor.isDefinedAt(left.dataType)) {
      TypeCheckFailure(s"Input type '${left.dataType}' does not conform to a raster type.")
    } else if (!tileExtractor.isDefinedAt(middle.dataType)) {
      TypeCheckFailure(s"Input type '${middle.dataType}' does not conform to a raster type.")
    } else if (!intArgExtractor.isDefinedAt(right.dataType)) {
      TypeCheckFailure(s"Input type '${right.dataType}' isn't an integral type.")
    } else TypeCheckSuccess
  }
  override def dataType: DataType = left.dataType

  override protected def nullSafeEval(leftInput: Any, middleInput: Any, rightInput: Any): Any = {
    implicit val tileSer = TileUDT.tileSerializer
    val (leftTile, leftCtx) = tileExtractor(left.dataType)(row(leftInput))
    val (rightTile, rightCtx) = tileExtractor(middle.dataType)(row(middleInput))

    if (leftCtx.isEmpty && rightCtx.isDefined)
      logger.warn(
          s"Right-hand parameter '${middle}' provided an extent and CRS, but the left-hand parameter " +
            s"'${left}' didn't have any. Because the left-hand side defines output type, the right-hand context will be lost.")

    if (leftCtx.isDefined && rightCtx.isDefined && leftCtx != rightCtx)
      logger.warn(s"Both '${left}' and '${middle}' provided an extent and CRS, but they are different. Left-hand side will be used.")

    val maskValue = intArgExtractor(right.dataType)(rightInput)

    val masking = if (maskValue.value == 0) Defined(rightTile)
    else rightTile

    val result = if (inverse)
      gtInverseMask(leftTile, masking, maskValue.value, raster.NODATA)
    else
      gtMask(leftTile, masking, maskValue.value, raster.NODATA)

    leftCtx match {
      case Some(ctx) => ctx.toProjectRasterTile(result).toInternalRow
      case None      => result.toInternalRow
    }
  }
}
object Mask {
  import org.locationtech.rasterframes.encoders.StandardEncoders.singlebandTileEncoder

  @ExpressionDescription(
    usage = "_FUNC_(target, mask) - Generate a tile with the values from the data tile, but where cells in the masking tile contain NODATA, replace the data value with NODATA.",
    arguments = """
  Arguments:
    * target - tile to mask
    * mask - masking definition""",
    examples = """
  Examples:
    > SELECT _FUNC_(target, mask);
       ..."""
  )
  case class MaskByDefined(target: Expression, mask: Expression)
    extends Mask(target, mask, Literal(0), false) {
    override def nodeName: String = "rf_mask"
  }
  object MaskByDefined {
    def apply(targetTile: Column, maskTile: Column): TypedColumn[Any, Tile] =
      new Column(MaskByDefined(targetTile.expr, maskTile.expr)).as[Tile]
  }

  @ExpressionDescription(
    usage = "_FUNC_(target, mask) - Generate a tile with the values from the data tile, but where cells in the masking tile DO NOT contain NODATA, replace the data value with NODATA",
    arguments = """
  Arguments:
    * target - tile to mask
    * mask - masking definition""",
    examples = """
  Examples:
    > SELECT _FUNC_(target, mask);
       ..."""
  )
  case class InverseMaskByDefined(leftTile: Expression, rightTile: Expression)
    extends Mask(leftTile, rightTile, Literal(0), true) {
    override def nodeName: String = "rf_inverse_mask"
  }
  object InverseMaskByDefined {
    def apply(srcTile: Column, maskingTile: Column): TypedColumn[Any, Tile] =
      new Column(InverseMaskByDefined(srcTile.expr, maskingTile.expr)).as[Tile]
  }

  @ExpressionDescription(
    usage = "_FUNC_(target, mask, maskValue) - Generate a tile with the values from the data tile, but where cells in the masking tile contain the masking value, replace the data value with NODATA.",
    arguments = """
  Arguments:
    * target - tile to mask
    * mask - masking definition""",
    examples = """
  Examples:
    > SELECT _FUNC_(target, mask, maskValue);
       ..."""
  )
  case class MaskByValue(leftTile: Expression, rightTile: Expression, maskValue: Expression)
    extends Mask(leftTile, rightTile, maskValue, false) {
    override def nodeName: String = "rf_mask_by_value"
  }
  object MaskByValue {
    def apply(srcTile: Column, maskingTile: Column, maskValue: Column): TypedColumn[Any, Tile] =
      new Column(MaskByValue(srcTile.expr, maskingTile.expr, maskValue.expr)).as[Tile]
  }

  @ExpressionDescription(
    usage = "_FUNC_(target, mask, maskValue) - Generate a tile with the values from the data tile, but where cells in the masking tile DO NOT contain the masking value, replace the data value with NODATA.",
    arguments = """
  Arguments:
    * target - tile to mask
    * mask - masking definition
    * maskValue - value in the `mask` for which to mark `target` as data cells
    """,
    examples = """
  Examples:
    > SELECT _FUNC_(target, mask, maskValue);
       ..."""
  )
  case class InverseMaskByValue(leftTile: Expression, rightTile: Expression, maskValue: Expression)
    extends Mask(leftTile, rightTile, maskValue, true) {
    override def nodeName: String = "rf_inverse_mask_by_value"
  }
  object InverseMaskByValue {
    def apply(srcTile: Column, maskingTile: Column, maskValue: Column): TypedColumn[Any, Tile] =
      new Column(InverseMaskByValue(srcTile.expr, maskingTile.expr, maskValue.expr)).as[Tile]
  }

  @ExpressionDescription(
    usage = "_FUNC_(data, mask, maskValues, inverse) - Generate a tile with the values from `data` tile but where cells in the `mask` tile are in the `maskValues` list, replace the value with NODATA. If `inverse` is true, the cells in `mask` that are not in `maskValues` list become NODATA",
    arguments =
      """

        """,
    examples =
      """
         > SELECT _FUNC_(data, mask, array(1, 2, 3), false)

        """
  )
  case class MaskByValues(dataTile: Expression, maskTile: Expression, maskValues: Expression, inverse: Boolean)
  extends Mask(dataTile, IsIn(maskTile, maskValues), inverse, false) {
    override def nodeName: String = "rf_mask_by_values"
  }
  object MaskByValues {
    def apply(dataTile: Column, maskTile: Column, maskValues: Column, inverse: Column): TypedColumn[Any, Tile] =
      new Column(MaskByValues(dataTile.expr, maskTile.expr, maskValues.expr, inverse.expr)).as[Tile]
  }

}
