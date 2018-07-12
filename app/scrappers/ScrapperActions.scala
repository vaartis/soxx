package soxx.scrappers

abstract class ScrapperAction
case class StartIndexing(fromPage: Int = 1, toPage: Option[Int] = None) extends ScrapperAction
case object StopIndexing extends ScrapperAction

case object StartDownloading extends ScrapperAction
case object StopDownloading extends ScrapperAction

case object ScrapperStatusMsg extends ScrapperAction
case class ScrapperStatus(imboard: String, isIndexing: Boolean, isDownloading: Boolean)
object ScrapperStatus {
  implicit val spStatusFormat = play.api.libs.json.Json.format[ScrapperStatus]
}
