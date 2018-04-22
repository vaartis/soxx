const path = require("path")

module.exports = {
    devtool: "source-map",
    entry: {
        admin_panel: "./js/admin_panel.jsx"
    },

    output: {
        filename: "[name].js",
        path: path.resolve(__dirname, "../../public/js")
    },

    module: {
        rules: [
            {
                test: /\.css$/,
                use: ["style-loader", "css-loader"]
            },
            {
                test: /\.jsx?/,
                loader: "babel-loader"
            }
        ]
    },

    mode: "development"
}
