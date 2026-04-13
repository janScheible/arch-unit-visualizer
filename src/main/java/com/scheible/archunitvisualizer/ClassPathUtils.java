package com.scheible.archunitvisualizer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * @author sj
 */
class ClassPathUtils {

	static String readResourceAsString(String name) {
		try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(name)) {
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	static InputStream readResource(String name) {
		return Thread.currentThread().getContextClassLoader().getResourceAsStream(name);
	}

}
