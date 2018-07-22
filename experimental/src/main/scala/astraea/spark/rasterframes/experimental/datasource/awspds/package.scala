/*
 * This software is licensed under the Apache 2 license, quoted below.
 *
 * Copyright 2018 Astraea. Inc.
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
 *
 */

package astraea.spark.rasterframes.experimental.datasource
import astraea.spark.rasterframes.util._
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.rf.CanBeColumn
/**
 * Module support.
 *
 * @since 5/4/18
 */
package object awspds {
  import astraea.spark.rasterframes.encoders.SparkDefaultEncoders._
  /**
   * Constructs link with the form:
   * `https://modis-pds.s3.amazonaws.com/MCD43A4.006/23/15/2013003/MCD43A4.A2013003.h23v15.006.2016125143738_${bandID}.TIF`
   * @param bandID Band ID suffix, e.g. "B04"
   * @return
   */
  def modis_band_url(bandID: String): TypedColumn[Any, String] = {
    concat(col("download_url"), concat(col("gid"), lit(s"_$bandID.TIF")))
  }.as(bandID).as[String]

  def download(urlColumn: Column): TypedColumn[Any, Array[Byte]] = {
    DownloadExpression(urlColumn.expr, urlColumn.columnName).asColumn
  }.as[Array[Byte]]

  def download_tiles(urlColumn: Column): Column = {
    DownloadTilesExpression(urlColumn.expr, urlColumn.columnName).asColumn
  }
}
