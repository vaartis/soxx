<template>
    <div class="card-columns">
        <div class="card img-card" v-for="image in images" key="image._id" v-bind:style="`background-image: url(${image.originalImage})`" />
    </div>
</template>

<script>
 export default {
     data() {
         return {
             images: []
         }
     },

     mounted() {
         fetch("/images")
             .then(resp => {
                 resp.json().then(imgs => {
                     this.images = imgs;
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
     width: 20em;
     height: 30em;
     position: relative;
     background-position: 50% 50%;
     background-repeat: no-repeat;
     background-size: cover;
 }

</style>
