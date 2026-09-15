// Headless Chromium with WebGL drawn in software, so a machine with no GPU and no display still has
// a real browser — and a real WebGL — to run these in. CHROME_BIN says which Chromium; see the
// module's build file.
config.set({
    browsers: ["ChromiumNoGpu"],
    customLaunchers: {
        ChromiumNoGpu: {
            base: "ChromeHeadless",
            flags: ["--no-sandbox", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--force-device-scale-factor=1"],
        },
    },
    browserNoActivityTimeout: 300000,
    browserDisconnectTimeout: 300000,
});

// A test that loads a font or reads back a frame takes longer than mocha's two seconds on a
// software rasteriser.
config.client = config.client || {};
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 120000 });

// A page cannot open a file, and cannot write one. So the tests ask the Karma server instead:
//
//   GET  /composegl/resources/<path>          a file from src/wasmJsTest/resources — a font, a golden
//   GET  /composegl/desktop-goldens/<name>    one of the raw OpenGL backend's goldens
//   POST /composegl/pictures/<kind>/<name>    a PNG the test drew, as a data URL in the body
//
// A `golden` picture is written over the golden itself, and only when COMPOSEGL_UPDATE_GOLDENS=1;
// an `actual` or a `difference` lands in build/screenshots, beside each other, like the desktop
// backends' do. GET /composegl/updating says which of the two a run is.
(function () {
    const fs = require("fs");
    const path = require("path");
    const resources = process.env.COMPOSEGL_WEB_RESOURCES;
    const screenshots = process.env.COMPOSEGL_WEB_SCREENSHOTS;
    const updating = process.env.COMPOSEGL_UPDATE_GOLDENS === "1";
    const desktopGoldens = process.env.COMPOSEGL_DESKTOP_GOLDENS;

    function middleware() {
        return function (request, response, next) {
            const url = decodeURIComponent(request.url.split("?")[0]);
            if (request.method === "GET" && url === "/composegl/updating") {
                response.setHeader("Content-Type", "text/plain");
                response.end(updating ? "1" : "0");
                return;
            }
            const roots = [["/composegl/resources/", resources], ["/composegl/desktop-goldens/", desktopGoldens]];
            const root = roots.find(([prefix, dir]) => dir && url.startsWith(prefix));
            if (request.method === "GET" && root) {
                const file = path.join(root[1], url.substring(root[0].length));
                if (!file.startsWith(root[1]) || !fs.existsSync(file)) {
                    response.statusCode = 404;
                    response.end("no " + file);
                    return;
                }
                response.setHeader("Cache-Control", "no-store");
                response.end(fs.readFileSync(file));
                return;
            }
            const picture = /^\/composegl\/pictures\/(golden|actual|difference)\/([A-Za-z0-9._-]+)$/.exec(url);
            if (request.method === "POST" && picture) {
                let body = "";
                request.on("data", (chunk) => (body += chunk));
                request.on("end", () => {
                    const kind = picture[1];
                    const name = picture[2];
                    const bytes = Buffer.from(body.replace(/^data:image\/png;base64,/, ""), "base64");
                    let out = null;
                    if (kind === "golden" && updating && resources) out = path.join(resources, "goldens", name + ".png");
                    if (kind !== "golden" && screenshots) out = path.join(screenshots, name + "-" + kind + ".png");
                    if (out) {
                        fs.mkdirSync(path.dirname(out), { recursive: true });
                        fs.writeFileSync(out, bytes);
                    }
                    response.end(out || "");
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
