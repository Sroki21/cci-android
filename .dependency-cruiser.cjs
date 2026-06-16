/** @type {import('dependency-cruiser').IConfiguration} */
module.exports = {
  forbidden: [
    {
      name: "no-circular",
      severity: "error",
      comment: "Cykliczne zależności są błędem — skrypty muszą mieć acykliczny graf zależności",
      from: {},
      to: { circular: true },
    },
    {
      name: "no-orphans",
      severity: "warn",
      comment: "Pliki bez żadnych importów ani importerów są prawdopodobnie martwym kodem",
      from: {
        orphan: true,
        pathNot: ["\\.json$"],
      },
      to: {},
    },
    {
      name: "no-deprecated-core",
      severity: "warn",
      comment: "Przestarzałe moduły Node.js core",
      from: {},
      to: {
        dependencyTypes: ["core"],
        path: "^(punycode|domain|constants|sys|_linklist|_stream_wrap)$",
      },
    },
    {
      name: "not-to-unresolvable",
      severity: "error",
      comment: "Import wskazuje na moduł, którego nie można rozwiązać",
      from: {},
      to: { couldNotResolve: true },
    },
    {
      name: "no-non-package-json-deps",
      severity: "error",
      comment: "Tylko zależności zadeklarowane w package.json mogą być importowane",
      from: {},
      to: {
        dependencyTypes: ["npm-no-pkg", "npm-unknown"],
      },
    },
  ],

  options: {
    doNotFollow: {
      path: "node_modules",
    },
    exclude: {
      path: ["node_modules", "\\.json$"],
    },
    includeOnly: {
      path: "^scripts/",
    },
    tsPreCompilationDeps: false,
    combinedDependencies: false,
    reporterOptions: {
      dot: {
        theme: {
          graph: { rankdir: "LR" },
          modules: [
            {
              criteria: { source: "^scripts/" },
              attributes: { fillcolor: "#ddeeff" },
            },
          ],
        },
      },
      archi: {
        collapsePattern: "^node_modules/[^/]+",
      },
    },
  },
};
