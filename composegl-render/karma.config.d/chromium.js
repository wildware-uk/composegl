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

// Mocha fails a test that takes longer than its limit, two seconds by default. The plain-box sweep in
// QuadBatchPlainBoxTest takes about half a second on a quiet machine, but on a CI runner busy with
// other modules' work it went past two on 26079062 and was failed for its time with no check
// failing. Ten seconds leaves room for a busy runner and still flags a test that has become many
// times slower. It does not stop a test that never returns: these tests are synchronous, so mocha
// only measures one after it has finished. A page stuck like that stops answering Karma's pings and
// is ended by browserDisconnectTimeout above.
config.client = config.client || {};
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 10000 });
