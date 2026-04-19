package com.scheible.archunitvisualizer;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.json.JsonWriter;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.spencerwi.either.Result;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jungrapht.visualization.layout.algorithms.EiglspergerLayoutAlgorithm;
import org.jungrapht.visualization.layout.model.LayoutModel;
import org.jungrapht.visualization.layout.model.Point;

/**
 * @author sj
 */
public class ArchUnitVisualizer {

	public static void packageLayers(JavaClasses classes, String referenceBranch, Class<?> rootClass)
			throws IOException {
		String currentBranch = Result.attempt(() -> Files.readString(Path.of("./.git/HEAD")))
				.flatMap(gitHead -> Result
				.attempt(() -> Stream.of(gitHead.split("/")).reduce((first, second) -> second).get()))
				.map(String::trim)
				.getOrElseThrow(() -> new IllegalStateException(
				"Can't determine the current Git branch. Is the projet directory a git repository at all?"));

		String currentReportJson = packageLayersReportJson(classes, currentBranch, referenceBranch, rootClass);

		Files.writeString(Path.of("./target/package-layers-" + currentBranch + ".json"), currentReportJson);
		if (!currentBranch.equals(referenceBranch)) {
			String referenceReportJson = Files
					.readString(Path.of("./target/package-layers-" + referenceBranch + ".json"), StandardCharsets.UTF_8);

			currentReportJson = packageLayersDiffReportJson(referenceReportJson, currentReportJson);
		}

		String htmlReport = ClassPathUtils.readResourceAsString("archunit-visualizer/package-layers.html");
		Files.writeString(Path.of("./target/package-layers.html"),
				htmlReport.replace("/* {{ */ undefined /* }} */", currentReportJson));
	}

	private static String packageLayersReportJson(JavaClasses classes, String currentBranch, String referenceBranch,
			Class<?> rootClass) throws IOException {
		// the graph used for the package layering
		Graph<String, DefaultEdge> packageDependencyGraph = new DefaultDirectedGraph<>(DefaultEdge.class);

		// the graph for class and package dependencies
		Map<String, Set<String>> allDependencyGraph = new HashMap<>();

		Map<String, Set<String>> packageClassesMapping = new HashMap<>();

		for (JavaClass clazz : classes) {
			String sourcePackageName = clazz.getPackageName();
			String sourceClassName = clazz.getFullName().substring(sourcePackageName.length() + 1);

			if (sourcePackageName.startsWith(rootClass.getPackageName())) {
				packageClassesMapping.computeIfAbsent(sourcePackageName, _ -> new HashSet<>()).add(sourceClassName);
			}

			for (Dependency dependency : clazz.getDirectDependenciesFromSelf()) {
				String targetPackageName = dependency.getTargetClass().getPackageName();
				if (dependency.getTargetClass().getFullName().length() < targetPackageName.length()) {
					continue;
				}
				String targetClassName = dependency.getTargetClass()
						.getFullName()
						.substring(targetPackageName.length() + 1);

				if (targetPackageName.startsWith(rootClass.getPackageName())) {
					packageClassesMapping.computeIfAbsent(targetPackageName, _ -> new HashSet<>()).add(targetClassName);
				}

				if (sourcePackageName.startsWith(rootClass.getPackageName())
						&& targetPackageName.startsWith(rootClass.getPackageName())) {
					if (!sourcePackageName.equals(targetPackageName)) {
						packageDependencyGraph.addVertex(sourcePackageName);
						packageDependencyGraph.addVertex(targetPackageName);
						packageDependencyGraph.addEdge(sourcePackageName, targetPackageName);

						allDependencyGraph.computeIfAbsent(sourcePackageName, _ -> new HashSet<>()).add(targetPackageName);
					}

					if (!(sourcePackageName + "." + sourceClassName).equals(targetPackageName + "." + targetClassName)) {
						allDependencyGraph.computeIfAbsent(sourcePackageName + "." + sourceClassName, _ -> new HashSet<>())
								.add(targetPackageName + "." + targetClassName);
					}
				}
			}
		}

		return toGraphJson(packageDependencyGraph, packageClassesMapping, allDependencyGraph,
				new PopulationDiff(SetDiff.EMPTY, SetDiff.EMPTY));
	}

	private static String packageLayersDiffReportJson(String referenceReportJson, String currentReportJson)
			throws IOException {
		Graph<String, DefaultEdge> packageDependencyGraph = new DefaultDirectedGraph<>(DefaultEdge.class);

		Map<String, Set<String>> allDependencyGraph = new HashMap<>();

		Map<String, Set<String>> packageClassesMapping = new HashMap<>();

		PopulationResult referencePopulationResult = populate(packageDependencyGraph, allDependencyGraph,
				packageClassesMapping, new StringReader(referenceReportJson));
		PopulationResult currentPopulationResult = populate(packageDependencyGraph, allDependencyGraph,
				packageClassesMapping, new StringReader(currentReportJson));

		PopulationDiff popualtionDiff = PopulationDiff.diff(referencePopulationResult, currentPopulationResult);

		return toGraphJson(packageDependencyGraph, packageClassesMapping, allDependencyGraph, popualtionDiff);

	}

	private static List<List<String>> calcPackageLayers(Graph<String, DefaultEdge> packageDependencyGraph) {
		List<List<String>> layers = new ArrayList<>();

		EiglspergerLayoutAlgorithm<String, Integer> layoutAlgorithm = EiglspergerLayoutAlgorithm
				.<String, Integer>edgeAwareBuilder()
				.threaded(false)
				.build();
		LayoutModel<String> layoutModel = LayoutModel.<String>builder()
				.size(100, 100)
				.graph(packageDependencyGraph)
				.build();
		layoutAlgorithm.visit(layoutModel);

		Map<Integer, List<Entry<String, Point>>> layerCoordPackagesMapping = layoutModel.getLocations()
				.entrySet()
				.stream()
				.collect(Collectors.groupingBy(e -> (int) e.getValue().y));
		ArrayList<Integer> layerCoords = new ArrayList<>(layerCoordPackagesMapping.keySet());
		Collections.sort(layerCoords);

		for (int layerCoord : layerCoords) {
			List<String> layer = new ArrayList<>();
			layers.add(layer);

			List<Entry<String, Point>> packages = layerCoordPackagesMapping.getOrDefault(layerCoord, List.of());
			packages.stream()
					.sorted((a, b) -> Double.compare(a.getValue().x, b.getValue().x))
					.forEach(e -> layer.add(e.getKey()));
		}

		return layers;
	}

	private static PopulationResult populate(Graph<String, DefaultEdge> packageDependencyGraph,
			Map<String, Set<String>> allDependencyGraph, Map<String, Set<String>> packageClassesMapping,
			Reader graphJsonReader) {
		Set<JavaClassWithPackage> javaClassWithPackages = new HashSet<>();
		Set<PackageOrJavaClassDependency> dependencies = new HashSet<>();

		try (JsonReader reader = Json.createReader(graphJsonReader)) {
			JsonObject jsonObject = reader.readObject();

			JsonArray packageLayers = jsonObject.getJsonArray("packageLayers");
			for (JsonValue layer : packageLayers) {
				for (JsonValue pkg : layer.asJsonArray()) {
					String packageName = pkg.asJsonObject().getString("name");
					packageDependencyGraph.addVertex(packageName);
					for (JsonValue clss : pkg.asJsonObject().getJsonArray("classes")) {
						packageClassesMapping.computeIfAbsent(packageName, _ -> new HashSet<>())
								.add(clss.asJsonObject().getString("name"));

						javaClassWithPackages
								.add(new JavaClassWithPackage(packageName, clss.asJsonObject().getString("name")));
					}
				}
			}

			for (Entry<String, JsonValue> dependency : jsonObject.getJsonObject("dependencies").entrySet()) {
				String originName = dependency.getKey();

				for (var target : dependency.getValue().asJsonArray()) {
					String targetName = target.asJsonObject().getString("name");

					if (packageDependencyGraph.containsVertex(originName)
							&& packageDependencyGraph.containsVertex(targetName)) {
						packageDependencyGraph.addEdge(originName, targetName);
					}

					allDependencyGraph.computeIfAbsent(originName, _ -> new HashSet<>()).add(targetName);
					dependencies.add(new PackageOrJavaClassDependency(originName, targetName));
				}
			}
		}

		return new PopulationResult(javaClassWithPackages, dependencies);
	}

	private static String toGraphJson(Graph<String, DefaultEdge> packageDependencyGraph,
			Map<String, Set<String>> packageClassesMapping, Map<String, Set<String>> allDependencyGraph,
			PopulationDiff popualtionDiff) throws IOException {
		List<List<String>> packageLayers = calcPackageLayers(packageDependencyGraph);

		List<List<Map<String, Object>>> packageLayersJson = packageLayers.stream()
				.map(layer -> layer.stream()
				.map(pkg -> Map.of("name", pkg, "classes",
				packageClassesMapping.getOrDefault(pkg, Set.of())
						.stream()
						.sorted()
						.map(clss -> Map.of("name", clss, "removed",
						popualtionDiff.javaClassWithPackagesDiff.removed
								.contains(new JavaClassWithPackage(pkg, clss)),
						"added",
						popualtionDiff.javaClassWithPackagesDiff.added
								.contains(new JavaClassWithPackage(pkg, clss))))
						.toList()))
				.toList())
				.toList();

		Function<String, Boolean> isPackage = pckg -> packageDependencyGraph.vertexSet().contains(pckg);

		Map<String, List<Map<String, Object>>> dependenciesJson = allDependencyGraph.entrySet()
				.stream()
				.collect(
						Collectors
								.toMap(Entry::getKey,
										e -> e.getValue()
												.stream()
												.sorted()
												.map(name -> Map
												.<String, Object>of("name", name, "removed",
														!isPackage.apply(e.getKey()) && !isPackage.apply(name)
														&& popualtionDiff.dependenciesDiff.removed
																.contains(new PackageOrJavaClassDependency(e.getKey(), name)),
														"added",
														!isPackage.apply(e.getKey()) && !isPackage.apply(name)
														&& popualtionDiff.dependenciesDiff.added
																.contains(new PackageOrJavaClassDependency(e.getKey(), name))))
												.toList()));

		Map<String, Object> graphJson = Map.of("packageLayers", packageLayersJson, "dependencies", dependenciesJson);

		StringWriter out = new StringWriter();
		try (JsonWriter writer = Json.createWriter(out)) {
			writer.write(Json.createObjectBuilder(graphJson).build());
		}
		return out.toString();
	}

	private record JavaClassWithPackage(String packageName, String javaClassName) {

	}

	private record PackageOrJavaClassDependency(String from, String to) {

	}

	private record SetDiff<T>(Set<T> added, Set<T> removed) {

		private static SetDiff EMPTY = new SetDiff(Set.of(), Set.of());

		private static <T> SetDiff<T> diff(Set<T> previous, Set<T> current) {
			HashSet<T> added = new HashSet<>(current);
			added.removeAll(previous);

			HashSet<T> removed = new HashSet<>(previous);
			removed.removeAll(current);

			return new SetDiff<>(added, removed);
		}
	}

	private record PopulationDiff(SetDiff<JavaClassWithPackage> javaClassWithPackagesDiff,
			SetDiff<PackageOrJavaClassDependency> dependenciesDiff) {

		private static PopulationDiff diff(PopulationResult previous, PopulationResult current) {
			return new PopulationDiff(SetDiff.diff(previous.javaClassWithPackages(), current.javaClassWithPackages()),
					SetDiff.diff(previous.dependencies(), current.dependencies()));
		}
	}

	private record PopulationResult(Set<JavaClassWithPackage> javaClassWithPackages,
			Set<PackageOrJavaClassDependency> dependencies) {

	}

}
