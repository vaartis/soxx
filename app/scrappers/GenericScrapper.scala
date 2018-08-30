package soxx.scrappers

import
  scala.concurrent.duration._

import akka.actor._
import
  play.api.{ Logger, Configuration },
  play.api.inject.Injector,
  play.api.libs.json._, play.api.libs.ws._
import
  org.mongodb.scala._,
  org.mongodb.scala.model._,  org.mongodb.scala.model.Updates._,
  com.mongodb.client.result.UpdateResult

import
  soxx.mongowrapper._,
  soxx.scrappers.parts._

case class SenderRef(val ref: ActorRef) extends AnyVal

/** A base for all scrappers.
 *
 * Provides a method to minify the effort needed to add new scrappers.
 * You need to override some things and you get a functional scrapper
 * for most of the imageboards.
 */
abstract class GenericScrapper(
  val name: String,
  val baseUrl: String,
  favicon: String,

  protected val injector: Injector
) extends Actor with IndexingParts with DownloadingParts {

  // Injected stuff
  protected implicit val (ws, mongo, ec, config) =
    (injector.instanceOf[WSClient], injector.instanceOf[Mongo], injector.instanceOf[ScrapperExecutionContext], injector.instanceOf[Configuration])

  /** Defines the structure of the image returned by the imageboard.
    *
    * This structure will be used to deserialize the returned image
    * and you will need information in it when creating the actual [[soxx.scrappers.Image]] object
    *
    * @see [[soxx.scrappers.Image]]
    */
  type ScrapperImage

  /** The string that needs to be added to the base URL to access the API.
    *
    * The URL must not include a trailing slash
   */
  val apiAddition: String

  /** Maximum number of threads to fetch pages concurrenyly */
  val maxPageFetchingConcurrency: Int = 5

  /** Maximum number of threads for image fetching */
  val maxImageFetchingConcurrency: Int = 5

  /** Each page's size */
  val pageSize = 100

  val logger = Logger(self.path.name)

  /** JSON formatter for the image. */
  implicit val imageFormat: OFormat[ScrapperImage]

  implicit val akkaTimeout = akka.util.Timeout(5.seconds)

  protected val imageCollection = mongo.db.getCollection[Image]("images")

  protected def scrapperStatus = ScrapperStatus(
    imboard = name,
    isIndexing = !indexingMaterializer.isEmpty,
    isDownloading = !downloadMaterializer.isEmpty
  )

  override def receive = {
    case StartIndexing(fromPage, toPage) =>
      startIndexing(fromPage, toPage)(SenderRef(sender))

    case StopIndexing =>
      stopIndexing()(SenderRef(sender))

    case StartDownloading =>
      startDownloading()(SenderRef(sender))

    case StopDownloading =>
      stopDownloading()(SenderRef(sender))

    case ScrapperStatusMsg =>
        sender ! scrapperStatus
  }

  override def preStart() {
    mongo.db
      .getCollection[BoardInfo]("imboard_info")
      .updateOne(
        Document(
          "_id" -> name,
        ),
        combine(
          set("_id", name),
          set("favicon", f"${baseUrl}/${favicon}"),
          set("pageSize", pageSize)
        ),
        UpdateOptions().upsert(true)
      )
      .subscribe { (_: UpdateResult) =>
        logger.info("Updated 'imboard_info'")
      }
  }

  override def postStop: Unit = {
    logger.info("Stopped")
  }
}
