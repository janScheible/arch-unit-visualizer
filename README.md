# ArchUnit Visualizer

## Visualization

### Package Layers

[Similar to Pocketsaw's pacakge layer graph.](https://github.com/janScheible/pocketsaw?tab=readme-ov-file#motivation)


## Usage

### Java Code

```java
class ArchUnitRenderTest {

	@Test
	void testArchUnitPacakgeLayerVisualization() throws IOException {
		JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages(ArchUnitRenderTest.class.getPackageName());

		ArchUnitVisualizer.packageLayers(classes, "main", ArchUnitRenderTest.class);
	}

}
```

### Build

```xml
	<dependency>
		<groupId>com.scheible</groupId>
		<artifactId>arch-unit-visualizer</artifactId>
		<version>1.0.0-SNAPSHOT</version>
		<scope>test</scope>
	</dependency>
```

```xml
	<plugin>
		<artifactId>maven-clean-plugin</artifactId>
		<version>3.5.0</version>
		<configuration>
			<excludeDefaultDirectories>true</excludeDefaultDirectories>
			<filesets>
				<fileset>
					<directory>target</directory>
					<includes>
						<include>**/*</include>
					</includes>
					<excludes>
						<exclude>package-layers*.json</exclude>
						<exclude>package-layers.html</exclude>
					</excludes>
				</fileset>
			</filesets>
		</configuration>
	</plugin>
```
