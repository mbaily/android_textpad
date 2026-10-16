import * as esbuild from "esbuild";
import sveltePlugin from "esbuild-svelte";

await esbuild.build({
  entryPoints: ["editor.js"],
  bundle: true,
  minify: true,
  format: "esm",
  target: "chrome110",
  outfile: "../textpad_server/static/editor.js",
  legalComments: "external",
  conditions: ["svelte", "browser"],
  plugins: [
    sveltePlugin({
      compilerOptions: {
        runes: true,
        css: "external",
      },
    }),
  ],
});
