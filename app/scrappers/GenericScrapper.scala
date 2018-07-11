package soxx.scrappers

import akka.Done
import java.nio.file._
import java.util.Arrays
import scala.concurrent.duration._
import scala.util._
import scala.concurrent.{ Await, ExecutionContext, Future }

import akka.stream._
import akka.stream.scaladsl._
import akka.actor._
import akka.pattern.ask
import play.api.inject.Injector
import play.api.libs.json._
import play.api.libs.ws._
import play.api.{ Logger, Configuration }
import org.mongodb.scala._
import org.mongodb.scala.model._
import com.mongodb.client.result.UpdateResult
import org.mongodb.scala.model.Updates._
import org.mongodb.scala.model.Filters._

import soxx.mongowrapper._

/** A base for all scrappers.
 *
 * Provides a method to minify the effort needed to add new scrappers.
 * You need to override some things and you get a functional scrapper
 * for most of the imageboards.
 */
abstract class GenericScrapper(
  name: String,
  baseUrl: String,
  favicon: String,

  injector: Injector
) extends Actor {

  // Injected stuff
  protected implicit val (ws, mongo, ec, config) =
    (injector.instanceOf[WSClient], injector.instanceOf[Mongo], injector.instanceOf[ExecutionContext], injector.instanceOf[Configuration])

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

  /** Get the total image count.
    *
    * Returns the total number of images on the imageboard.
    * It doesn't need to bother about the maximum number of images, since bounding will
    * be handeled automatically in the indexing function
   */
  def getImageCount: Future[Int]

  /** Return the page's images.
    *
    * Provided with the page number, this function returns serialized images on this page.
    * This function needs to return the same page provided to it in the beginning,
    * this is needed for the Akka streams to send it down the stream.
    */
  def getPageImagesAndCurrentPage(page: Int): Future[(Seq[ScrapperImage], Int)]

  implicit val akkaTimeout = akka.util.Timeout(5.seconds)

  /** Converts the internal image to the actual image used in the database.
    *
    * This function should set the [[Image.from]] field to a [[scala.collection.Seq]] with a single element:
    * information about this imageboard.
    */
  def scrapperImageToImage(img: ScrapperImage): Option[Image]

  protected final var materializer: Option[ActorMaterializer] = None
  protected final var downloadMaterializer: Option[ActorMaterializer] = None

  protected final def startDownloading(): Unit = {
    if (downloadMaterializer.isEmpty) {
      implicit val actualMaterializer = ActorMaterializer()(context)
      downloadMaterializer = Some(actualMaterializer)

      for (
        imagesToDownload <-
        mongo.db
        .getCollection[Image]("images").find(combine(
          equal("metadataOnly", true), // Find the images that only have metadata
          equal("from.name", name)
        )).toFuture
      ) {
        logger.info(f"Images to download: ${imagesToDownload.length}")

        Source(imagesToDownload.to[collection.immutable.Iterable])
          .mapAsyncUnordered(maxImageFetchingConcurrency) { image =>

            val imageName = f"${image.md5}${image.extension}"

            // Just update the metadata
            // This is needed if several imageboards have the same image
            def updateMetadataOnlyFalse() =
                mongo.db
                  .getCollection[Image]("images")
                  .updateOne(equal("_id", image._id), set("metadataOnly", false))
                  .map { case v: UpdateResult if v.wasAcknowledged => logger.info(f"Image ${image._id} already saved, updated 'metadataOnly' state") }
                  .toFuture

            if (config.get[Boolean]("soxx.s3.enabled")) {
              import soxx.s3._

              val (region, endpoint, bucketName) = (
                config.getOptional[String]("soxx.s3.region"),
                config.get[String]("soxx.s3.endpoint"),
                config.get[String]("soxx.s3.bucket-name"),
              )

              context
                .actorSelection(context.system / "s3-uploader")
                .resolveOne(5.seconds)
                .flatMap { s3Uploader => (s3Uploader ? ImageExists(imageName)).map { a => (s3Uploader, a) } }
                .flatMap { case (s3Uploader, exists_) =>
                  val exists = exists_.asInstanceOf[Boolean]
                  if (exists) {
                    updateMetadataOnlyFalse()
                  } else {
                    ws.url(image.from.head.image)
                      .get()
                      .flatMap { resp =>
                        val stream = resp.bodyAsSource.runWith(StreamConverters.asInputStream(5.seconds))
                        val size = resp.body.length
                        val contentType = resp.contentType

                        // Start uploading image and update the metadataOnly mark
                        // No logging here since the uploader logs things already
                        s3Uploader
                          .ask(UploadImage(imageName, stream, size, contentType))
                          .flatMap {
                            case true =>
                              stream.close()

                              mongo.db.getCollection[Image]("images")
                                .updateOne(
                                  equal("_id", image._id),
                                  combine(
                                    set("metadataOnly", false),
                                    set("s3", true),
                                    set("s3url", f"""${region.map(_ + '.').getOrElse("")}$endpoint/$bucketName/$imageName""")
                                  )
                                )
                                .toFuture
                          }
                      }
                  }
                }
            } else {
              val imagesDir = Paths.get("images")
              if (Files.notExists(imagesDir)) {
                // Create the directory to store images in..
                Files.createDirectories(imagesDir)
              }

              val savePath = Paths.get(f"images/$imageName")
              if (Files.exists(savePath)) {
                updateMetadataOnlyFalse()
              } else {
                // Actually download the image
                ws.url(image.from.head.image).get()
                  .flatMap { _.bodyAsSource.runWith(FileIO.toPath(savePath)) } // Save the file
                  .flatMap { case IOResult(_, Success(Done)) => mongo.db.getCollection[Image]("images").updateOne(equal("_id", image._id), set("metadataOnly", false)).toFuture } // Set the metadataOnly to true
                  .map { case v: UpdateResult if v.wasAcknowledged => logger.info(f"Saved image ${image._id}") }
              }
            }
          }
          .runWith(Sink.ignore)
          .andThen {
            case Success(_) =>
              stopDownloading()
              logger.info("Finished downloading")
        }
      }
    }
  }

  protected final def startIndexing(fromPage: Int, toPage: Option[Int]): Unit = {
    if (materializer == None) {
      implicit val actualMaterializer = ActorMaterializer()(context)
      materializer = Some(actualMaterializer)

      for (
        imageCount <- getImageCount;
        _ <- mongo.db.getCollection[BoardInfo]("imboard_info").updateOne(equal("_id", name), set("reportedImageCount", imageCount)).toFuture
      ) {
        logger.info(f"Updated reported image count to $imageCount")

        val pageCount = {
          // Get the page count from the image count and page size
          val pageCount = imageCount / pageSize

          // Limit to `toPage` if needed
          toPage match {
            // If the pageCount is less, then just use it, otherwise use toPage
            case Some(toPage) => Math.min(pageCount, toPage)
            case None => pageCount
          }
        }
        logger.info(f"Total page count: ${pageCount}")

        Source(fromPage to (pageCount + 1))
          // Download pages
          .mapAsyncUnordered(maxPageFetchingConcurrency)(getPageImagesAndCurrentPage)
          .map { case (scrapperImages, currentPage) => // Again, page here is needed downstream..
            val operations =
              scrapperImages
                .map(scrapperImageToImage)
                .collect { case Some(i) => i } // Filter out all None's and return images
                .map { img =>
                  // If it's a new picture
                  if (Await.result(mongo.db.getCollection[Image]("images").find(equal("md5", img.md5)).toFuture(), 5 seconds).isEmpty) {
                    InsertOneModel(img)
                  } else {
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
            (operations, currentPage)
          }
          .mapAsyncUnordered(maxPageFetchingConcurrency) { case (operations, currentPage) =>
            mongo.db
              .getCollection[Image]("images")
              .bulkWrite(operations, BulkWriteOptions().ordered(false))
              .toFuture.map { r => (r, currentPage) }
          }
          .runForeach { case (res, currentPage) if res.wasAcknowledged => logger.info(s"Finished page ${currentPage}") }
          .recover {
            case _: AbruptStageTerminationException => logger.info("Materializer is already terminated")
          }
      }.andThen {
        case Success(_) =>
          stopIndexing()
          logger.info("Scrapping finished")
      }
    }
  }


  protected final def stopIndexing(): Unit = {
    materializer.foreach { mat =>
      mat.shutdown()
      materializer = None
    }
  }

  protected final def stopDownloading() {
      downloadMaterializer.foreach { mat =>
        mat.shutdown()
        downloadMaterializer = None
      }
  }

  override def receive = {
    case StartIndexing(fromPage, toPage) =>
      startIndexing(fromPage, toPage)

    case StopIndexing =>
      stopIndexing()
    case ScrapperStatusMsg =>
      sender ! ScrapperStatus(
        isIndexing = !materializer.isEmpty,
        isDownloading = !downloadMaterializer.isEmpty
      )

    case StartDownloading =>
      startDownloading()

    case StopDownloading =>
      stopDownloading()
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
