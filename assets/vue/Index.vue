<template>
    <div class="card-columns">
        <div class="card" v-for="image in images" v-bind:key="image._id">
            <!-- Hacky but works! Looks beter then object-fit too  -->
            <!-- Just using the first "from" may not be the best idea -->
            <a v-bind:href="`/image/${image._id}`"
               data-toggle="modal" v-bind:data-target="`#image-modal-${image._id}`">
                <div class="card-img-top img-card"
                     v-bind:style="`background-image: url(${image.from[0].image})`">
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
             images: []
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

             fetch(imagesUrl)
                 .then(resp => {
                     resp.json().then(imgs => {
                         this.images = imgs.result;
                     })
                 })
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
