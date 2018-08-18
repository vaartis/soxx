package soxx.scrappers

import java.nio.file._
import java.util.Arrays
import scala.util._
import
  scala.concurrent.{ Await, ExecutionContext, Future },
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

      /** Just update the metadata, this is needed if several imageboards have the same image */
      def updateMetadataOnlyFalse(image: Image): Future[Boolean] =
        imageCollection
          .updateOne(equal("_id", image._id), set("metadataOnly", false))
          .head
          .map { r =>
            if (r.wasAcknowledged) {
              logger.info(f"Image ${image._id} already saved, updated 'metadataOnly' state")
              true
            } else false
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

        (s3Uploader ? ImageExists(imageName))
          .map(_.asInstanceOf[Boolean])
          .flatMap { exists =>
            if (exists) updateMetadataOnlyFalse(image) else {
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
                          .head
                          .map { r => if (r.wasAcknowledged) true else false }
                    }
                }
            }
          }
      }

      /** Save images to files */
      def downloadToFile(image: Image): Future[Boolean] = {
        val imagesDir = Paths.get(config.get[String]("soxx.scrappers.downloadDirectory"))
        if (Files.notExists(imagesDir)) {
          // Create the directory to store images in..
          Files.createDirectories(imagesDir)
        }

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
            } // Set the metadataOnly to true
            .map { v =>
              if (v.wasAcknowledged) {
                true
              } else {
                false
              }
            }
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

  protected final def startIndexing(fromPage: Int, toPage: Option[Int])(implicit senderRef: SenderRef): Unit = {
    /** Gets the image count, writes it into the database, translates image count into page count and logs the total page count. */
    def getPageRange = {
      getImageCount
        .andThen { case Success(imageCount) => mongo.db.getCollection[BoardInfo]("imboard_info").updateOne(equal("_id", name), set("reportedImageCount", imageCount)).toFuture }
        .map { imageCount =>
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

          fromPage to pageCount
        }
    }

    /** Transform the page into the operations required to write it into the database. */
    def operationsForPage(page: Seq[Image], pageNum: Int) = {
      Future.sequence(
        page.map { img =>
          imageCollection.find(equal("md5", img.md5)).toFuture.map { maybeImages =>
            if (maybeImages.isEmpty) {
              // New picture
              InsertOneModel(img)
            } else {
              // Update the old one
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
        }
      ).map { ops => (ops, pageNum)  }
    }

    if (materializer.isEmpty) {
      implicit val actualMaterializer = ActorMaterializer()(context)
      materializer = Some(actualMaterializer)

      Source.fromFuture(getPageRange)
        .flatMapConcat(Source(_))
        .mapAsyncUnordered(maxPageFetchingConcurrency)(getPageImagesAndCurrentPage)
        .map { case (scrapperImages, currentPage) =>
          (
            scrapperImages.map(scrapperImageToImage).collect { case Some(i) => i },
            currentPage
          )
        }
        .mapAsyncUnordered(maxPageFetchingConcurrency)(Function.tupled(operationsForPage))
        .mapAsyncUnordered(maxPageFetchingConcurrency){ case (ops, currentPage) =>
          imageCollection.bulkWrite(ops, BulkWriteOptions().ordered(false)).toFuture.map { r => (r, currentPage) }
        }
        .runForeach { case (res, currentPage) if res.wasAcknowledged => logger.info(s"Finished page ${currentPage}") }
        .recover {
          case _: AbruptStageTerminationException => logger.info("Materializer is already terminated")
          case x => logger.error(f"Scrapping error: $x")
        }
        .andThen {
          case Success(_) =>
            stopIndexing()
            logger.info("Finished indexing")
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
