// Headless Chromium with WebGL drawn in software, so a machine with no GPU and no display still
// has a real browser to run the wasm tests in. CHROME_BIN says which Chromium; see the root build.
config.set({
    browsers: ["ChromiumNoGpu"],
    customLaunchers: {
        ChromiumNoGpu: {
            base: "ChromeHeadless",
            flags: ["--no-sandbox", "--use-angle=swiftshader", "--enable-unsafe-swiftshader"],
        },
    },
    browserNoActivityTimeout: 300000,
    browserDisconnectTimeout: 300000,
});
