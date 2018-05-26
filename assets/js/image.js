import ImageInfo from "../vue/ImageInfo.vue";

import URI from "urijs";
import _ from "lodash";

let vm = new Vue({
    el: "#image-info-main",

    data: {
        image: {}
    },

    template: `
        <ImageInfo v-bind:image="image" v-bind:isADedicatedPage="true" />
    `,

    mounted() {
        let currentUrl = new URI(window.location.href);
        let imageID = _.last(currentUrl.segment());

        fetch(`/api/v1/image/${imageID}`)
            .then(r => {
                r.json().then(img => {
                    this.image = img.result;
                });
            });
    },

    components: { ImageInfo }
});
