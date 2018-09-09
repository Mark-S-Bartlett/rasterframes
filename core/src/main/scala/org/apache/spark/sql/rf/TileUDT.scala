/*
 * This software is licensed under the Apache 2 license, quoted below.
 *
 * Copyright 2018 Astraea, Inc.
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

package org.apache.spark.sql.rf

import astraea.spark.rasterframes.ref.RasterRef.RasterRefTile
import astraea.spark.rasterframes.tiles.ProjectedRasterTile.SourceKind
import astraea.spark.rasterframes.tiles.{InternalRowTile, ProjectedRasterTile}
import geotrellis.raster._
import org.apache.spark.sql.Encoders
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.types.{DataType, _}
import org.apache.spark.unsafe.types.UTF8String

/**
 * UDT for singleband tiles.
 *
 * @since 5/11/17
 */
@SQLUserDefinedType(udt = classOf[TileUDT])
class TileUDT extends UserDefinedType[Tile] {
  override def typeName = "rf_tile"

  override def pyUDT: String = "pyrasterframes.TileUDT"

  def sqlType: StructType = TileUDT.schema

  override def serialize(obj: Tile): InternalRow =
    Option(obj)
      .map(TileUDT.encode)
      .orNull

  override def deserialize(datum: Any): Tile =
    Option(datum)
      .collect {
        case ir: InternalRow ⇒ TileUDT.decode(ir)
      }
      .map {
        case realIRT: InternalRowTile ⇒ realIRT.toArrayTile()
        case other ⇒ other // Currently the DelayedReadTile
      }
      .orNull

  def userClass: Class[Tile] = classOf[Tile]

  private[sql] override def acceptsType(dataType: DataType) = dataType match {
    case _: TileUDT ⇒ true
    case _ ⇒ super.acceptsType(dataType)
  }
}

case object TileUDT extends TileUDT {
  UDTRegistration.register(classOf[Tile].getName, classOf[TileUDT].getName)

  private val prtEncoder = Encoders
    .kryo(classOf[ProjectedRasterTile])
    .asInstanceOf[ExpressionEncoder[ProjectedRasterTile]]
    .resolveAndBind()

  /** Union encoding of Tiles and RasterRefs */
  val schema = StructType(Seq(
    StructField("tile", InternalRowTile.schema, true),
    StructField("tileRef", prtEncoder.schema, true)
  ))


  /** Determine if the row encodes a Tile. */
  def isTile(row: InternalRow): Boolean =
    !row.isNullAt(0) && row.isNullAt(1)

  /** Determine if the row encodes a RasterRef. */
  def isRef(row: InternalRow): Boolean =
    row.isNullAt(0) && !row.isNullAt(1)

  /**
   * Read a Tele from an InternalRow
   * @param row Catalyst internal format conforming to `schema`
   * @return row wrapper
   */
  def decode(row: InternalRow): Tile = {
    (!row.isNullAt(0), !row.isNullAt(1)) match {
      case (true, false) ⇒ new InternalRowTile(row.getStruct(0, InternalRowTile.schema.size))
      case (false, true) ⇒ prtEncoder.fromRow(row.getStruct(1, prtEncoder.schema.size))
      case _ ⇒ throw new IllegalArgumentException("Unexpected row InternalRow shape")
    }
  }

  /**
   * Convenience extractor for converting a `Tile` to an `InternalRow`.
   *
   * @param tile tile to convert
   * @return Catalyst internal representation.
   */
  def encode(tile: Tile): InternalRow = tile match {
    case pt: ProjectedRasterTile if pt.sourceKind == SourceKind.Reference ⇒
      InternalRow(null, prtEncoder.toRow(pt))
    case _: Tile ⇒
      InternalRow(
        InternalRow(
          UTF8String.fromString(tile.cellType.name),
          tile.cols.toShort,
          tile.rows.toShort,
          tile.toBytes
        ), null)
  }
}