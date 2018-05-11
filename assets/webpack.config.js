const path = require("path")
const VueLoaderPlugin = require('vue-loader/lib/plugin')

module.exports = {
    devtool: "source-map",
    entry: {
        index: "./js/index.js"
    },

    resolve: {
        alias: {
            vue: 'vue/dist/vue.js'
        }
    },

    output: {
        filename: "[name].js",
        path: path.resolve(__dirname, "../public/js")
    },

    module: {
        rules: [
            {
                test: /\.css$/,
                use: ["style-loader", "css-loader"]
            },
            {
                test: /\.vue$/,
                use: ["vue-loader"]
            }
        ]
    },

    plugins: [
        new VueLoaderPlugin()
    ],

    mode: "development"
}
