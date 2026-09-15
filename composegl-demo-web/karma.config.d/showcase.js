// Headless Chromium with WebGL drawn in software, the same as composegl-webgl's tests: no GPU and no
// display needed. CHROME_BIN says which Chromium; see the root build file.
config.set({
    browsers: ["ChromiumNoGpu"],
    customLaunchers: {
        ChromiumNoGpu: {
            base: "ChromeHeadless",
            flags: ["--no-sandbox", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--force-device-scale-factor=1"],
        },
    },
    browserNoActivityTimeout: 600000,
    browserDisconnectTimeout: 600000,
});

// Every page of the showcase on a software rasteriser takes longer than mocha's two seconds.
config.client = config.client || {};
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 300000 });

// The page cannot open a file, so the Karma server hands the tests the showcase's fonts and art, and
// writes the pictures they send back:
//
//   GET  /composegl/resources/<path>     a file from composegl-demo/src/main/resources
//   POST /composegl/pictures/<name>      a PNG, as a data URL, written to build/screenshots/<name>.png
(function () {
    const fs = require("fs");
    const path = require("path");
    const resources = process.env.COMPOSEGL_WEB_RESOURCES;
    const screenshots = process.env.COMPOSEGL_WEB_SCREENSHOTS;

    function middleware() {
        return function (request, response, next) {
            const url = decodeURIComponent(request.url.split("?")[0]);
            if (request.method === "GET" && resources && url.startsWith("/composegl/resources/")) {
                const file = path.join(resources, url.substring("/composegl/resources/".length));
                if (!file.startsWith(resources) || !fs.existsSync(file)) {
                    response.statusCode = 404;
                    response.end("no " + file);
                    return;
                }
                response.end(fs.readFileSync(file));
                return;
            }
            const picture = /^\/composegl\/pictures\/([A-Za-z0-9._-]+)$/.exec(url);
            if (request.method === "POST" && picture && screenshots) {
                let body = "";
                request.on("data", (chunk) => (body += chunk));
                request.on("end", () => {
                    fs.mkdirSync(screenshots, { recursive: true });
                    const out = path.join(screenshots, picture[1] + ".png");
                    fs.writeFileSync(out, Buffer.from(body.replace(/^data:image\/png;base64,/, ""), "base64"));
                    response.end(out);
                });
                return;
            }
            next();
        };
    }

    config.plugins = config.plugins || [];
    config.plugins.push({ "middleware:composegl": ["factory", middleware] });
    config.beforeMiddleware = (config.beforeMiddleware || []).concat(["composegl"]);
})();
