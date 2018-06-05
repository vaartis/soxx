package soxx.scrappers

import play.api.libs.json._

case class BoardInfo(
  _id: String,
  favicon: String,
  pageSize: Int,
  estimatePages: Option[Int] = None,
  lastIndexedPage: Option[Int] = None
)

object BoardInfo {
  implicit val boardInfoFormat = Json.format[BoardInfo]
}
