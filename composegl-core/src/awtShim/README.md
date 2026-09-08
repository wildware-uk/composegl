# The AWT shim

Fifteen stub classes that stand in for the small corner of AWT that Compose Multiplatform's
desktop artifact reaches for while a scene is being built.

They exist to answer one question, and they are never on the classpath of anything ComposeGL
ships: **can Compose's scene machinery run on a runtime that has no AWT at all?** Android and
RoboVM are such runtimes, so the answer sizes the Android port
([#25](https://github.com/wildware-uk/composegl/issues/25)).

`./gradlew :composegl-core:noAwtTest` runs the whole core suite on a JVM started without the
`java.desktop` module, with these on the boot class path. It passes, and the three places that
need the shim are the complete list — see
[`docs/superpowers/spikes/s5-no-awt-runtime.md`](../../../docs/superpowers/spikes/s5-no-awt-runtime.md).

Every stub does the least it can: `Toolkit.getDefaultToolkit()` throws `HeadlessException`, because
the caller catches exactly that; `SwingUtilities.invokeLater` runs inline; `Cursor` is an int in a
box. If a stub ever has to grow a real implementation, that is the finding, not the fix.
