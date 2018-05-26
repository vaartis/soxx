const path = require("path")
const VueLoaderPlugin = require('vue-loader/lib/plugin')
const webpack = require("webpack");

module.exports = {
    devtool: "source-map",
    entry: {
        index: "./js/index.js",
        image: "./js/image.js",

        top_panel: "./js/top_panel.js",
        bootstrap: "./js/bootstrap.js",
    },

    resolve: {
        alias: {
            vue: 'vue/dist/vue.js'
        }
    },

    output: {
        filename: "[name].js",
        path: path.resolve(__dirname, "../public/js"),
        publicPath: "/assets/js/"
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
        new VueLoaderPlugin(),
        new webpack.ProvidePlugin({
            Vue: ["vue"]
        })
    ],

    mode: "development"
}
