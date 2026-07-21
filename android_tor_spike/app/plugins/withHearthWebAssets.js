const { withAppBuildGradle } = require("@expo/config-plugins");

// vp1: re-inject the copyHearthWeb Gradle task after `expo prebuild --clean`
// regenerates android/. Idempotent: skips if the marker is already present.
const MARKER = "vp1:copyHearthWeb";
const SNIPPET = `
// ${MARKER} — single source of truth for the WebView shell UI (see withHearthWebAssets.js)
def hearthWebDir = new File(rootProject.projectDir, "../../../hearth/web")
tasks.register('copyHearthWeb', Copy) {
    from hearthWebDir
    into "\${projectDir}/src/main/assets/www"
}
preBuild.dependsOn 'copyHearthWeb'
`;

module.exports = function withHearthWebAssets(config) {
  return withAppBuildGradle(config, (cfg) => {
    if (!cfg.modResults.contents.includes(MARKER)) {
      cfg.modResults.contents += SNIPPET;
    }
    return cfg;
  });
};
