package soxx.scrappers

import play.api.libs.json._

case class BoardInfo(
  _id: String,
  favicon: String,
  pageSize: Int,
  reportedPageCount: Option[Int] = None,
  indexedImageCount: Option[Int] = None
)

object BoardInfo {
  implicit val boardInfoFormat = Json.format[BoardInfo]
}
