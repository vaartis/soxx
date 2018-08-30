package soxx.scrappers

import java.nio.file._
import scala.util._
import
  scala.concurrent.Future,
  scala.concurrent.duration._

import
  akka.Done,
  akka.actor._,
  akka.stream._, akka.stream.scaladsl._,
  akka.pattern.ask
import
  play.api.{ Logger, Configuration },
  play.api.inject.{ Injector, BindingKey },
  play.api.libs.json._, play.api.libs.ws._
import
  org.mongodb.scala._,
  org.mongodb.scala.model._,  org.mongodb.scala.model.Updates._, org.mongodb.scala.model.Filters._,
  com.mongodb.client.result.UpdateResult
import cats._, cats.data._, cats.implicits._

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

  injector: Injector
) extends Actor with IndexingParts {

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

  protected final var downloadMaterializer: Option[ActorMaterializer] = None

  protected final def startDownloading()(implicit senderRef: SenderRef): Unit = {
    if (downloadMaterializer.isEmpty) {
      implicit val actualMaterializer = ActorMaterializer()(context)
      downloadMaterializer = Some(actualMaterializer)

      /** Just update the metadata, this is needed if several imageboards have the same image */
      def updateMetadataOnlyFalse(image: Image): Future[Boolean] =
        imageCollection
          .updateOne(equal("_id", image._id), set("metadataOnly", false))
          .head
          .map { r =>
            if (r.wasAcknowledged) {
              logger.info(f"Image ${image._id} already saved, updated 'metadataOnly' state")
            }
            r.wasAcknowledged
          }

      /** Save images to S3 */
      def downloadToS3(image: Image): Future[Boolean] = {
        import soxx.s3._

        val imageName = f"${image.md5}${image.extension}"

        val (region, endpoint, bucketName) = (
          config.getOptional[String]("soxx.s3.region"),
          config.get[String]("soxx.s3.endpoint"),
          config.get[String]("soxx.s3.bucket-name"),
        )

        val s3Uploader = injector.instanceOf(BindingKey(classOf[ActorRef]).qualifiedWith("s3-uploader"))

        Monad[Future].ifM(
          (s3Uploader ? ImageExists(imageName)).map(_.asInstanceOf[Boolean])
        )(
          ifTrue = updateMetadataOnlyFalse(image),
          ifFalse =
            ws.url(image.from.head.image)
              .get()
              .flatMap { resp =>
                val stream = resp.bodyAsSource.runWith(StreamConverters.asInputStream(5.seconds))
                val size = resp.body.length
                val contentType = resp.contentType

                // Start uploading image and update the metadataOnly mark
                // No logging here since the uploader logs things already
                Monad[Future].ifM(
                  s3Uploader.ask(UploadImage(imageName, stream, size, contentType)).map(_.asInstanceOf[Boolean])
                )(
                  ifTrue = {
                    imageCollection
                      .updateOne(
                        equal("_id", image._id),
                        combine(
                          set("metadataOnly", false),
                          set("s3", true),
                          set("s3url", f"""${region.map(_ + '.').getOrElse("")}$endpoint/$bucketName/$imageName""")
                        )
                      ).head.map(_.wasAcknowledged)
                  },
                  ifFalse = Future.successful(false)
                ).andThen { case _ => stream.close() }
              }
        )
      }

      /** Save images to files */
      def downloadToFile(image: Image): Future[Boolean] = {
        val imagesDir = Paths.get(config.get[String]("soxx.scrappers.downloadDirectory"))
        if (Files.notExists(imagesDir))
          Files.createDirectories(imagesDir) // Create the directory to store images in..

        val imageName = f"${image.md5}${image.extension}"
        val savePath = Paths.get(imagesDir.toString, imageName)
        if (Files.exists(savePath)) {
          updateMetadataOnlyFalse(image)
        } else {
          // Actually download the image
          ws.url(image.from.head.image).get()
            .flatMap { _.bodyAsSource.runWith(FileIO.toPath(savePath)) } // Save the file
            .flatMap {
              case IOResult(_, Success(Done)) => imageCollection.updateOne(equal("_id", image._id), set("metadataOnly", false)).toFuture
              case IOResult(_, Failure(e)) => throw e;
            }.map(_.wasAcknowledged)
        }
      }

      val shouldDownloadToS3 = config.get[Boolean]("soxx.s3.enabled")

      Source.fromFuture(
        imageCollection
          .find(combine(
            equal("metadataOnly", true), // Find the images that only have metadata
            equal("from.name", name)
          )).toFuture)
        .flatMapConcat { imgs =>
          logger.info(f"Images to download: ${imgs.length}")

          Source(imgs.to[collection.immutable.Iterable])
        }
        .mapAsyncUnordered(maxImageFetchingConcurrency) { image =>
          (if (shouldDownloadToS3) downloadToS3(image) else downloadToFile(image)).map { isOk => (isOk, image._id) /* Use image ID to log success */ }
        }
        .recover { case x => logger.error(f"Downloading error: $x") }
        .runWith(Sink.foreachParallel(maxPageFetchingConcurrency){ case (isOk: Boolean, imageId: org.bson.types.ObjectId) =>
          if (isOk)
            logger.info(f"Finished downloading image ${imageId.toString}")
          else
            logger.error(f"Failed to download image ${imageId.toString}")
        })
        .andThen {
          case Success(_) =>
            stopDownloading()
            logger.info("Finished downloading")
        }
    }
  }

  private def scrapperStatus = ScrapperStatus(
    imboard = name,
    isIndexing = !indexingMaterializer.isEmpty,
    isDownloading = !downloadMaterializer.isEmpty
  )


  protected final def stopIndexing()(implicit senderRef: SenderRef): Unit = {
    indexingMaterializer.foreach { mat =>
      mat.shutdown()
      indexingMaterializer = None
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
