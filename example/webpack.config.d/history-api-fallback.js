// Serve index.html for direct SPA route requests such as /board and /tasks.
config.devServer = config.devServer || {};
config.devServer.historyApiFallback = true;
