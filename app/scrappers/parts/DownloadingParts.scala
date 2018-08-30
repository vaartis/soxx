package soxx.scrappers.parts

import soxx.scrappers._

import java.nio.file.{ Files, Paths }
import
  scala.util.{ Success, Failure },
  scala.concurrent.Future,
  scala.concurrent.duration._

import
  akka.Done,
  akka.actor.ActorRef,
  akka.pattern.ask,
  akka.stream.{ ActorMaterializer, IOResult },
  akka.stream.scaladsl.{ Source, Sink, StreamConverters, FileIO }
import
  org.mongodb.scala.model.Filters._,
  org.mongodb.scala.model.Updates._
import play.api.inject.BindingKey
import cats._, cats.data._, cats.implicits._

trait DownloadingParts { scrapper: GenericScrapper =>

  /* The actor materializer responsible for downloading.
   *
   * Easy to kill to stop downloading.
   */
  protected var downloadMaterializer: Option[ActorMaterializer] = None

  /** Just updates the metadata, doesn't download it; this is needed if several imageboards have the same image.
    *
    * Also logs if successful.
    *
    * @param image the image needing the update
    * @return successfulness of the update
    */
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

  /** Saves the image into the S3-compaitable object storage using the image md5 as it's name
    *
    * @image the image to be saved
    * @return successfulness of the operation
    */
  def downloadToS3(image: Image)(implicit mat: ActorMaterializer): Future[Boolean] = {
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
      // Just update the metadata if the image was already uploaded
      ifTrue = updateMetadataOnlyFalse(image),
      // Upload the image otherwise
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

  /** Saves the image to a file.
    *
    * The filename is derived from the image md5. This function also creates the
    * directory to store images if it didn't exist before.
    *
    * @param the image to save
    * @return the successfulness of the operation
    */
  def downloadToFile(image: Image)(implicit mat: ActorMaterializer): Future[Boolean] = {
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
          case IOResult(_, Success(Done)) =>
            imageCollection.updateOne(equal("_id", image._id), set("metadataOnly", false)).toFuture
          case IOResult(_, Failure(e)) => throw e;
        }.map(_.wasAcknowledged)
    }
  }

  /** Actuall starts downloading the images.
    *
    * Downloadins images to files/s3. Does everything in parallel. Notifies
    * the sender when the downloading process is finished.
    */
  protected final def startDownloading()(implicit senderRef: SenderRef): Unit = {
    if (downloadMaterializer.isEmpty) {
      implicit val actualMaterializer = ActorMaterializer()(context)
      downloadMaterializer = Some(actualMaterializer)

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
          (if (shouldDownloadToS3) downloadToS3(image) else downloadToFile(image))
            .map { isOk => (isOk, image._id) /* Use image ID to log success */ }
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

  /** Stops the downloading process by killing the materializer. */
  protected final def stopDownloading()(implicit senderRef: SenderRef): Unit = {
    downloadMaterializer.foreach { mat =>
      mat.shutdown()
      downloadMaterializer = None
    }

    senderRef.ref ! scrapperStatus
  }

}
