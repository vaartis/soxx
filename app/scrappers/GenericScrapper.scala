package soxx.scrappers

import java.util.Arrays
import scala.concurrent.duration._

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import play.api.libs.json._
import akka.actor._
import play.api.Logger
import scala.concurrent.{ Await, ExecutionContext, Future }

import soxx.mongowrapper._
import org.mongodb.scala._
import org.mongodb.scala.model._
import com.mongodb.client.result.UpdateResult

/** A base for all scrappers.
 *
 * Provides a method to minify the effort needed to add new scrappers.
 * You need to override some things and you get a functional scrapper
 * for most of the imageboards.
 */
abstract class GenericScrapper ()
  (
    implicit ws: WSClient,
    mongo: Mongo,
    ec: ExecutionContext
  ) extends Actor {

  /** Defines the structure of the image returned by the imageboard.
    *
    * This structure will be used to deserialize the returned image
    * and you will need information in it when creating the actual [[soxx.scrappers.Image]] object
    *
    * @see [[soxx.scrappers.Image]]
    */
  type ScrapperImage

  /** Name of the scrapper and/or the imageboard.
    *
    * This name is used for storing the data in MongoDB
   */
  val name: String

  /** Base imageboard URL from which others are derived.
    *
    * The URL must not include a trailing slash
    */
  val baseUrl: String

  /** The string that needs to be added to the base URL to access the API.
    *
    * The URL must not include a trailing slash
   */
  val apiAddition: String

  /** The imageboard favicon file relative to the [[baseUrl]] */
  val favicon: String = "favicon.ico"

  /** Maximum number of threads to fetch pages concurrenyly */
  val maxPageFetchingConcurrency: Int = 5

  val logger = Logger(this.getClass)

  /** JSON formatter for the image. */
  implicit val imageFormat: OFormat[ScrapperImage]

  /** Get the total page count.
    *
    * This function returns the total number of pages on the imageboard.
    * It doesn't need to bother about the maximum page, since bounding will
    * be handeled automatically in the indexing function
   */
  def getPageCount: Future[Int]

  /** Return the page's images.
    *
    * Provided with the page number, this function returns serialized images on this page.
    * This function needs to return the same page provided to it in the beginning,
    * this is needed for the Akka streams to send it down the stream.
    */
  def getPageImagesAndCurrentPage(page: Int): Future[(Seq[ScrapperImage], Int)]

  /** Converts the internal image to the actual image used in the database.
    *
    * This function should set the [[Image.from]] field to a [[scala.collection.Seq]] with a single element:
    * information about this imageboard.
    */
  def scrapperImageToImage(img: ScrapperImage): Image

  protected final var materializer: Option[ActorMaterializer] = None

  protected final def startIndexing(fromPage: Int, toPage: Option[Int]): Unit = {
    if (materializer == None) {
      implicit val actualMaterializer = ActorMaterializer()(context)
      materializer = Some(actualMaterializer)

      val imageCollection = mongo.db.getCollection[Image]("images")

      getPageCount
        .map { pagesCount =>
          // Limit to `toPage` if needed
          toPage match {
              case Some(toPage) => if (pagesCount < toPage) { pagesCount } else { toPage }
              case None => pagesCount
          }
        }
        .map { pagesCount =>
          Source(fromPage to (pagesCount + 1))
            .mapAsyncUnordered(maxPageFetchingConcurrency)(getPageImagesAndCurrentPage)
            .runForeach { case (scrapperImages, currentPage) =>
              import org.mongodb.scala.model.Filters._

              val operations = scrapperImages
                .map(scrapperImageToImage)
                .map { img =>
                  // If it's a new picture
                  if (Await.result(imageCollection.find(equal("md5", img.md5)).toFuture(), 5 seconds).isEmpty) {
                    InsertOneModel(img)
                  } else {
                    import org.mongodb.scala.model.Updates._

                    UpdateOneModel(
                      equal("md5", img.md5),
                      combine(
                        // Merge tags
                        addEachToSet("tags", img.tags:_*),

                        // Update score
                        set("from.$[board].score", img.from.head.score),
                      ),

                      // Find this imageboard's entry by name
                      UpdateOptions()
                        .arrayFilters(Arrays.asList(
                          equal("board.name", name)
                        ))
                    )
                  }
                }

              imageCollection
                .bulkWrite(operations, BulkWriteOptions().ordered(false))
                .subscribe(
                  (_: BulkWriteResult) => logger.info(s"Finished page ${currentPage}")
                )
            }
        }
    }
  }

  protected final def stopIndexing(): Unit = {
    materializer.foreach { mat =>
      mat.shutdown()
      materializer = None
    }
  }

  override def receive = {
    case StartIndexing(fromPage, toPage) =>
      startIndexing(fromPage, toPage)

    case StopIndexing =>
      stopIndexing()
  }

  override def preStart() {
    mongo.db
      .getCollection[BoardInfo]("imboard_info")
      .replaceOne(
        Document(
          "_id" -> name,
        ),
        BoardInfo(name, favicon = f"${baseUrl}/${favicon}"),
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
