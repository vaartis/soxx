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
import play.api.inject.{ Injector, BindingKey }
import play.api.libs.json._
import play.api.libs.ws._
import play.api.{ Logger, Configuration }
import org.mongodb.scala._
import org.mongodb.scala.model._
import com.mongodb.client.result.UpdateResult
import org.mongodb.scala.model.Updates._
import org.mongodb.scala.model.Filters._

import soxx.mongowrapper._

case class SenderRef(val ref: ActorRef) extends AnyVal

/** A base for all scrappers.
 *
 * Provides a method to minify the effort needed to add new scrappers.
 * You need to override some things and you get a functional scrapper
 * for most of the imageboards.
 */
abstract class GenericScrapper(
  name: String,
  val baseUrl: String,
  favicon: String,

  injector: Injector
) extends Actor {

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
    */
  def getPageImages(page: Int): Future[Seq[ScrapperImage]]

  /** Add the page number to images for akka stream processing */
  private def getPageImagesAndCurrentPage(page: Int): Future[(Seq[ScrapperImage], Int)] =
    getPageImages(page).map { imgs => (imgs, page) }

  implicit val akkaTimeout = akka.util.Timeout(5.seconds)

  private val imageCollection = mongo.db.getCollection[Image]("images")

  /** Converts the internal image to the actual image used in the database.
    *
    * This function should set the [[Image.from]] field to a [[scala.collection.Seq]] with a single element:
    * information about this imageboard.
    */
  def scrapperImageToImage(img: ScrapperImage): Option[Image]

  protected final var materializer: Option[ActorMaterializer] = None
  protected final var downloadMaterializer: Option[ActorMaterializer] = None

  protected final def startDownloading()(implicit senderRef: SenderRef): Unit = {
    if (downloadMaterializer.isEmpty) {
      implicit val actualMaterializer = ActorMaterializer()(context)
      downloadMaterializer = Some(actualMaterializer)

      for (
        imagesToDownload <-
        imageCollection
        .find(combine(
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
              imageCollection
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

              val s3Uploader = injector.instanceOf(BindingKey(classOf[ActorRef]).qualifiedWith("s3-uploader"))

              (s3Uploader ? ImageExists(imageName))
                .map { a => (s3Uploader, a) }
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

                              imageCollection
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
              val imagesDir = Paths.get(config.get[String]("soxx.scrappers.downloadDirectory"))
              if (Files.notExists(imagesDir)) {
                // Create the directory to store images in..
                Files.createDirectories(imagesDir)
              }

              val savePath = Paths.get(imagesDir.toString, imageName)
              if (Files.exists(savePath)) {
                updateMetadataOnlyFalse()
              } else {
                // Actually download the image
                ws.url(image.from.head.image).get()
                  .flatMap { _.bodyAsSource.runWith(FileIO.toPath(savePath)) } // Save the file
                  .flatMap {
                    case IOResult(_, Success(Done)) =>
                      imageCollection
                        .updateOne(equal("_id", image._id), set("metadataOnly", false))
                        .head
                        .map { v => if (v.wasAcknowledged) { logger.info(f"Saved image ${image._id}") } }
                    case IOResult(_, Failure(e)) => throw e;
                  }
              }
            }
          }
          .recover { case x => logger.error(f"Downloading error: $x") }
          .runWith(Sink.ignore)
          .andThen {
            case Success(_) =>
              stopDownloading()
              logger.info("Finished downloading")
        }
      }
    }
  }

  protected final def startIndexing(fromPage: Int, toPage: Option[Int])(implicit senderRef: SenderRef): Unit = {
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
          // Round up in case there's a small amount of images
          val pageCount = Math.ceil(imageCount.floatValue / pageSize.floatValue).intValue

          // Limit to `toPage` if needed
          toPage match {
            // If the pageCount is less, then just use it, otherwise use toPage
            case Some(toPage) => Math.min(pageCount, toPage)
            case None => pageCount
          }
        }
        logger.info(f"Total page count: ${pageCount}")

        Source(fromPage to pageCount)
          // Download pages
          .mapAsyncUnordered(maxPageFetchingConcurrency)(getPageImagesAndCurrentPage)
          .map { case (scrapperImages, currentPage) => // Again, page here is needed downstream..
            val operations =
              scrapperImages
                .map(scrapperImageToImage)
                .collect { case Some(i) => i } // Filter out all None's and return images
                .map { img =>
                  // If it's a new picture
                  if (Await.result(imageCollection.find(equal("md5", img.md5)).toFuture(), 5 seconds).isEmpty) {
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
            imageCollection
              .bulkWrite(operations, BulkWriteOptions().ordered(false))
              .toFuture.map { r => (r, currentPage) }
          }
          .runForeach { case (res, currentPage) if res.wasAcknowledged => logger.info(s"Finished page ${currentPage}") }
          .recover {
            case _: AbruptStageTerminationException => logger.info("Materializer is already terminated")
            case x => logger.error(f"Scrapping error: $x")
          }
      }.andThen {
        case Success(_) =>
          stopIndexing()
          logger.info("Scrapping finished")
      }
    }
  }

  private def scrapperStatus = ScrapperStatus(
    imboard = name,
    isIndexing = !materializer.isEmpty,
    isDownloading = !downloadMaterializer.isEmpty
  )


  protected final def stopIndexing()(implicit senderRef: SenderRef): Unit = {
    materializer.foreach { mat =>
      mat.shutdown()
      materializer = None
    }

    senderRef.ref ! scrapperStatus
  }

  protected final def stopDownloading()(implicit senderRef: SenderRef): Unit = {
      downloadMaterializer.foreach { mat =>
        mat.shutdown()
        downloadMaterializer = None
      }

    senderRef.ref ! scrapperStatus
  }

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
