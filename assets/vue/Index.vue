<template>
    <div>
        <div class="card-columns">
            <div class="card" v-for="image in images" v-bind:key="image._id">
                <!-- Hacky but works! Looks beter then object-fit too  -->
                <!-- Just using the first "from" may not be the best idea -->
                <a v-bind:href="`/image/${image._id}`"
                   data-toggle="modal" v-bind:data-target="`#image-modal-${image._id}`">
                    <div class="card-img-top img-card"
                         v-bind:style="genImgLink(image)">
                    </div>
                </a>
                <ImageModal v-bind:image="image" />
                <div class="card-body">
                    <div class="card-header">
                        <a v-for="from in image.from" v-bind:key="from.name"
                           v-bind:href="from.post" v-bind:title="from.name">
                            <img v-bind:src="favicon(from.name)" />
                        </a>
                    </div>
                </div>
            </div>
        </div>

        <div>
            <ul class="pagination justify-content-center">
                <li class="page-item" v-for="page in pages">
                    <a class="page-link" v-bind:href="computePageUrl(page)" v-on:click.prevent="goToPage(page)"> {{ page }} </a>
                </li>
            </ul>
        </div>
    </div>
</template>

<script>
 import urlListener from "url-listener";
 import _ from "lodash";
 import URI from "urijs";

 import ImageModal from "./ImageModal.vue";

 Vue.config.productionTip = false

 export default {
     data() {
         return {
             imboard_info: {},
             images: [],
             pages: [] // Actually just page numbers since you kinda can't use lodash in templates(?)
         }
     },

     methods: {
         favicon(imgFrom) {
             return this.imboard_info[imgFrom].favicon;
         },

         updateImagesFromQueryString() {
             let queryUrl = new URI(window.location);
             let imagesUrl = new URI(queryUrl.origin() + "/api/v1/images");

             if (queryUrl.hasQuery("query")) {
                 let searchString = queryUrl.query(true)["query"];
                 imagesUrl.addQuery("query", searchString);
             }

             let page = 1;
             if (queryUrl.hasQuery("page")) {
                 page = queryUrl.query(true)["page"];
                 imagesUrl.addQuery("offset", page * 25); // The default image count is 25
             }

             fetch(imagesUrl)
                 .then(resp => {
                     resp.json().then(r => {
                         this.images = r.result.images;

                         // Get a `range` of elements around the `index`
                         function getAround(array, index, range) {
                             var least = index - range - 1;
                             least = (least < 0) ? 0 : least;
                             return _.slice(array, least, least + (range * 2) + 1);
                         }


                         this.pages = getAround(
                             _.range(1, Math.floor(r.result.imageCount / 25)), // FIXME: make page size configurable
                             page,
                             5
                         );

                         // Go to the top of the page
                         window.scroll(0, 0);
                     });
                 })
         },

         computePageUrl(page) {
             let queryUrl = new URI(window.location);
             queryUrl.setQuery("page", page);

             return queryUrl.toString();
         },

         goToPage(page) {
             window.history.pushState({page: page}, '', this.computePageUrl(page));
         },

         genImgLink(image) {
             let link = image.metadataOnly ? image.from[0].image : `/image_files/${image.md5}${image.extension}`;

             return { backgroundImage: `url(${link})` };
         }
     },

     mounted() {
         let queryUrl = new URI(window.location);

         fetch("/api/v1/imboard_info")
             .then(resp => {
                 resp.json().then(boards => {
                     _.forEach(boards, (board) => {
                         Vue.set(this.imboard_info, board._id, _.omit(board, "_id"));
                     })
                 })
             })
             .then(this.updateImagesFromQueryString)

         urlListener(event => {
             this.updateImagesFromQueryString();
         });
     },

     components: { ImageModal }
 }
</script>

<style>
 .card-columns {
     column-count: 5;
     max-width: 100%;
 }

 .img-card {
     height: 30em;
     width: auto;
     position: relative;
     background-position: 50% 50%;
     background-repeat: no-repeat;
     background-size: cover;
 }

</style>
