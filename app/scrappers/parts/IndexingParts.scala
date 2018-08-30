package soxx.scrappers.parts

import java.util.Arrays
import scala.util.Success
import scala.concurrent.Future

import
  akka.stream.{ ActorMaterializer, AbruptStageTerminationException },
  akka.stream.scaladsl.Source
import
  org.mongodb.scala.model.Filters._,
  org.mongodb.scala.model.Updates._,
  org.mongodb.scala.model.{ InsertOneModel, UpdateOneModel, UpdateOptions, BulkWriteOptions }

import soxx.scrappers._

/** A mixin that contains all the indexing stuff for the scrapper.
  */
trait IndexingParts { scrapper: GenericScrapper =>

  /** Get the total image count.
    *
    * Returns the total number of images on the imageboard.
    * It doesn't need to bother about the maximum number of images, since bounding will
    * be handeled automatically in the indexing function
    */
  def getImageCount: Future[Int]

  /** Converts the internal image to the actual image used in the database.
    *
    * This function should set the [[Image.from]] field to a [[scala.collection.Seq]] with a single element:
    * information about this imageboard.
    */
  def scrapperImageToImage(img: ScrapperImage): Option[Image]

  /** Return the page's images.
    *
    * Provided with the page number, this function returns serialized images on this page.
    */
  def getPageImages(page: Int): Future[Seq[ScrapperImage]]

  /** Add the page number to images for akka stream processing */
  private def getPageImagesAndCurrentPage(page: Int): Future[(Seq[ScrapperImage], Int)] =
    getPageImages(page).map { imgs => (imgs, page) }

  /** The actor materializer to control indexing flow.
    *
    * It can also be killed easily, if there's a need to stop indexing.
    */
  protected var indexingMaterializer: Option[ActorMaterializer] = None

  /** Gets the image count, writes it into the database, translates image count into
    * page count and logs the total page count.
    *
    * @param from page to start indexing from
    * @param toPage optionally, limit the page to the given one
    * @return a range of pages from the beginning to the end, those will be indexed afterwards
    */
  def getPageRange(fromPage: Int, toPage: Option[Int]) = {
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

  /** Transform the page into the operations required to write it into the database.
    *
    * @param page the sequence of images (probably the newly fetched page) to transform into mongodb operations
    */
  def operationsForPage(page: Seq[Image]) = {
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
    )
  }

  /* Does the actual indexing of the images, aggregating the results and putting them into the database.
   * 
   * Everything's done in parallel. It also reports when the indexing is finished.
   */
  protected final def startIndexing(fromPage: Int, toPage: Option[Int])(implicit senderRef: SenderRef): Unit = {

    if (indexingMaterializer.isEmpty) {
      implicit val actualMaterializer = ActorMaterializer()(context)
      indexingMaterializer = Some(actualMaterializer)

      Source.fromFuture(getPageRange(fromPage, toPage))
        .flatMapConcat(Source(_))
        .mapAsyncUnordered(maxPageFetchingConcurrency)(getPageImagesAndCurrentPage) // Get the images for that page
        .map { case (scrapperImages, currentPage) =>
          (
            scrapperImages.map(scrapperImageToImage).collect { case Some(i) => i }, // Transform them into a uniform format
            currentPage
          )
        }
        .mapAsyncUnordered(maxPageFetchingConcurrency){ case (page: Seq[Image], currentPage: Int) => operationsForPage(page).map((_, currentPage)) } // Transform them into mongodb operations
        .mapAsyncUnordered(maxPageFetchingConcurrency){ case (ops, currentPage) =>
          imageCollection.bulkWrite(ops, BulkWriteOptions().ordered(false)).toFuture.map { r => (r, currentPage) } // Write them to the database
        }
        .runForeach { case (res, currentPage) if res.wasAcknowledged => logger.info(s"Finished page ${currentPage}") } // If the write was acked, log that we indexed the page
        .recover {
          case _: AbruptStageTerminationException => logger.info("Materializer is already terminated") // A quick fix for an error that appears in logs when you kill the materializer
          case x => logger.error(f"Scrapping error: $x")
        }
        .andThen { // Yaay, we indexed everything! Now kill the materializer and log success~
          case Success(_) =>
            stopIndexing()
            logger.info("Finished indexing")
        }
    }
  }

  /** Stops indexing by killing the materializer and notifies the sender about that. */
  protected final def stopIndexing()(implicit senderRef: SenderRef): Unit = {
    indexingMaterializer.foreach { mat =>
      mat.shutdown()
      indexingMaterializer = None
    }

    senderRef.ref ! scrapperStatus
  }

}
