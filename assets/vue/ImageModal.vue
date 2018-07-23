<template>
    <div class="modal fade" role="dialog" tabIndex="-1"
         v-bind:id="`image-modal-${this.image._id}`">
        <div class="modal-dialog modal-lg" role="document">
            <div class="modal-content">
                <div class="modal-header">
                    <h5 class="modal-title">Image {{ image._id }}</h5>
                    <button type="button" class="close" data-dismiss="modal">
                        &times;
                    </button>
                </div>
                <div class="modal-body">
                    <ImageInfo v-bind:image="image" v-bind:isADedicatedPage="false" />
                </div>
            </div>
        </div>
    </div>
</template>

<script>
 import ImageInfo from "./ImageInfo.vue";

 export default {
     props: {
         image: {
             type: Object,
             required: true
         }
     },

     components: { ImageInfo }
 }
</script>

<style>
 .modal-lg {
     max-width: 80%;
 }
</style>
