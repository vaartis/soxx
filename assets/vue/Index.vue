<template>
    <div class="card-columns">
        <div class="card" v-for="image in images" v-bind:key="image._id">
            <!-- Hacky but works! Looks beter then object-fit too  -->
            <!-- Just using the first "from" may not be the best idea -->
            <div class="card-img-top img-card"
                 v-bind:style="`background-image: url(${image.from[0].image})`" />
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
 import _ from "lodash";

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
         }
     },

     mounted() {
         fetch("/api/v1/imboard_info")
             .then(resp => {
                 resp.json().then(boards => {
                     _.forEach(boards, (board) => {
                         Vue.set(this.imboard_info, board._id, _.omit(board, "_id"));
                     })
                 })
             })
         fetch("/api/v1/images")
             .then(resp => {
                 resp.json().then(imgs => {
                     this.images = imgs.result;
                 })
             })
     }
 }
</script>

<style>
 .card-columns {
     column-count: 5;
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
