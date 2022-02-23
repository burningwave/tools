/*
 * This file is part of Burningwave Tools.
 *
 * Author: Roberto Gentili
 *
 * Hosted at: https://github.com/burningwave/tools
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2022 Roberto Gentili
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.burningwave.tools.dependencies;

import static org.burningwave.core.assembler.StaticComponentContainer.BackgroundExecutor;
import static org.burningwave.core.assembler.StaticComponentContainer.Driver;
import static org.burningwave.core.assembler.StaticComponentContainer.ManagedLoggerRepository;
import static org.burningwave.core.assembler.StaticComponentContainer.Paths;
import static org.burningwave.core.assembler.StaticComponentContainer.Streams;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.burningwave.core.Component;
import org.burningwave.core.assembler.ComponentContainer;
import org.burningwave.core.assembler.ComponentSupplier;
import org.burningwave.core.classes.ByteCodeHunter;
import org.burningwave.core.classes.JavaClass;
import org.burningwave.core.concurrent.QueuedTaskExecutor;
import org.burningwave.core.concurrent.QueuedTaskExecutor.Task;
import org.burningwave.core.function.TriConsumer;
import org.burningwave.core.io.FileSystemItem;

public class Capturer implements Component {
	protected static final String BURNINGWAVE_CLASSES_RELATIVE_DESTINATION_PATH = "[org.burningwave]";
	protected static final String TOOLFACTORY_CLASSES_RELATIVE_DESTINATION_PATH = "[io.github.toolfactory]";
	ByteCodeHunter byteCodeHunter;

	Capturer(ByteCodeHunter byteCodeHunter) {
		this.byteCodeHunter = byteCodeHunter;
	}

	public static Capturer create(ComponentSupplier componentSupplier) {
		return new Capturer(componentSupplier.getByteCodeHunter());
	}

	public static Capturer getInstance() {
		return LazyHolder.getCapturerInstance();
	}

	public Result capture(String mainClassName, Collection<String> baseClassPaths,
			TriConsumer<String, String, ByteBuffer> resourceConsumer, boolean includeMainClass,
			Long continueToCaptureAfterSimulatorClassEndExecutionFor) {
		return capture(mainClassName, new String[0], baseClassPaths, resourceConsumer, includeMainClass,
				continueToCaptureAfterSimulatorClassEndExecutionFor);
	}

	@SuppressWarnings("resource")
	public Result capture(String mainClassName, String[] mainMethodAruments, Collection<String> baseClassPaths,
			TriConsumer<String, String, ByteBuffer> resourceConsumer, boolean includeMainClass,
			Long continueToCaptureAfterSimulatorClassEndExecutionFor) {
		final Result result = new Result();
		Function<JavaClass, Boolean> javaClassAdder = includeMainClass ? (javaClass) -> {
			result.put(javaClass);
			return true;
		} : (javaClass) -> {
			if (!javaClass.getName().equals(mainClassName)) {
				result.put(javaClass);
				return true;
			}
			return false;
		};
		result.findingTask = BackgroundExecutor.createTask(task -> {
			Class<?> cls;
			try (Sniffer resourceSniffer = new Sniffer(null).init(false, baseClassPaths, javaClassAdder,
					fileSystemItem -> {
						result.putResource(fileSystemItem);
						return true;
					}, resourceConsumer)) {
				try {
					cls = Class.forName(mainClassName, false, resourceSniffer);
					cls.getMethod("main", String[].class).invoke(null, (Object) mainMethodAruments);
					if (continueToCaptureAfterSimulatorClassEndExecutionFor != null
							&& continueToCaptureAfterSimulatorClassEndExecutionFor > 0) {
						Thread.sleep(continueToCaptureAfterSimulatorClassEndExecutionFor);
					}
				} catch (Throwable exc) {
					Driver.throwException(exc);
				} finally {
					createExecutor(result.getStore().getAbsolutePath(), mainClassName, mainMethodAruments,
							UUID.randomUUID().toString());
				}
			}
		}).submit();
		return result;
	}

	public Result captureAndStore(String mainClassName, Collection<String> baseClassPaths, String destinationPath,
			boolean includeMainClass, Long continueToCaptureAfterSimulatorClassEndExecutionFor) {
		return captureAndStore(mainClassName, new String[0], baseClassPaths, destinationPath, includeMainClass,
				continueToCaptureAfterSimulatorClassEndExecutionFor);
	}

	public Result captureAndStore(String mainClassName, String[] mainMethodAruments, Collection<String> baseClassPaths,
			String destinationPath, boolean includeMainClass,
			Long continueToCaptureAfterSimulatorClassEndExecutionFor) {
		Result dependencies = capture(mainClassName, mainMethodAruments, baseClassPaths,
				getStoreFunction(destinationPath), includeMainClass,
				continueToCaptureAfterSimulatorClassEndExecutionFor);
		dependencies.store = FileSystemItem.ofPath(destinationPath);
		return dependencies;
	}

	TriConsumer<String, String, ByteBuffer> getStoreFunction(String destinationPath) {
		// Exclude the runtime jdk library
		final String javaHome = Paths.clean(System.getProperty("java.home")) + "/";
		BiPredicate<String, FileSystemItem> storePredicate = (resourceAbsolutePath,
				fileSystemItem) -> !resourceAbsolutePath.startsWith(javaHome) && !fileSystemItem.exists();
		return (resourceAbsolutePath, resourceRelativePath, resourceContent) -> {
			String finalPath;
			if (resourceRelativePath.startsWith("io/github/toolfactory")) {
				finalPath = destinationPath + "/" + TOOLFACTORY_CLASSES_RELATIVE_DESTINATION_PATH;
			} else if (resourceRelativePath.startsWith("org/burningwave")) {
				finalPath = destinationPath + "/" + BURNINGWAVE_CLASSES_RELATIVE_DESTINATION_PATH;
			} else {
				finalPath = getStoreEntryBasePath(destinationPath, resourceAbsolutePath, resourceRelativePath);
			}
			FileSystemItem fileSystemItem = FileSystemItem.ofPath(finalPath + "/" + resourceRelativePath);
			if (storePredicate.test(resourceAbsolutePath, fileSystemItem)) {
				Streams.store(fileSystemItem.getAbsolutePath(), resourceContent);
				ManagedLoggerRepository.logInfo(getClass()::getName, "Resource {} has been stored to class path {}",
						resourceRelativePath, fileSystemItem.getAbsolutePath());
			}
		};
	}

	String getStoreEntryBasePath(String storeBasePath, String itemAbsolutePath, String ItemRelativePath) {
		String finalPath = itemAbsolutePath;
		if (finalPath.chars().filter(ch -> ch == '/').count() > 1) {
			finalPath = finalPath.substring(0, finalPath.lastIndexOf(ItemRelativePath) - 1)
					.substring(finalPath.indexOf("/") + 1);
			finalPath = "[" + finalPath.replace("/", "][") + "]";
		} else {
			finalPath = finalPath.replace("/", "");
		}
		return storeBasePath + "/" + getReducedPath(finalPath);
	}

	private String getReducedPath(String path) {
		String temp = path.substring(0, path.lastIndexOf("["));
		temp = temp.substring(0, temp.lastIndexOf("["));
		return path.substring(temp.lastIndexOf("["));
	}

	void createExecutor(String destinationPath, String mainClassName, String[] mainMethodAruments,
			String executorSuffix) {
		if (System.getProperty("os.name").toLowerCase().contains("windows")) {
			createWindowsExecutor(destinationPath, mainClassName, mainMethodAruments, executorSuffix);
		} else {
			createUnixExecutor(destinationPath, mainClassName, mainMethodAruments, executorSuffix);
		}
	}

	void createWindowsExecutor(String destinationPath, String mainClassName, String[] mainMethodAruments,
			String executorSuffix) {
		try {
			Set<String> classPathSet = FileSystemItem.ofPath(destinationPath).refresh()
					.findInChildren(FileSystemItem.Criteria.forAllFileThat(FileSystemItem::isFolder)).stream()
					.map(fileSystemItem -> fileSystemItem.getAbsolutePath().replace(destinationPath + "/", "%~dp0"))
					.collect(Collectors.toSet());
			String externalExecutorForWindows = "@echo off\n\""
					+ FileSystemItem.ofPath(System.getProperty("java.home")).getAbsolutePath()
					+ "/bin/java\" -classpath \"" + String.join(File.pathSeparator, classPathSet)
					+ "\" " + mainClassName
					+ (mainMethodAruments.length > 0
							? " " + String.join(" ",
									toDoubleQuotedStringsForStringsThatContainEmptySpace(mainMethodAruments))
							: "");
			Files.write(java.nio.file.Paths.get(destinationPath + "/executor-" + executorSuffix + ".cmd"),
					externalExecutorForWindows.getBytes());
		} catch (Throwable exc) {
			ManagedLoggerRepository.logError(getClass()::getName, "Exception occurred", exc);
		}
	}

	void createUnixExecutor(String destinationPath, String mainClassName, String[] mainMethodAruments,
			String executorSuffix) {
		try {
			Set<String> classPathSet = FileSystemItem.ofPath(destinationPath).refresh()
					.findInChildren(FileSystemItem.Criteria.forAllFileThat(FileSystemItem::isFolder)).stream()
					.map(fileSystemItem -> fileSystemItem.getAbsolutePath()).collect(Collectors.toSet());
			String externalExecutorForUnix = FileSystemItem.ofPath(System.getProperty("java.home")).getAbsolutePath()
					+ "/bin/java -classpath " + String.join(File.pathSeparator, classPathSet) + " "
					+ mainClassName
					+ (mainMethodAruments.length > 0
							? " " + String.join(" ",
									toDoubleQuotedStringsForStringsThatContainEmptySpace(mainMethodAruments))
							: "");
			Files.write(java.nio.file.Paths.get(destinationPath + "/executor-" + executorSuffix + ".sh"),
					externalExecutorForUnix.getBytes());
		} catch (Throwable exc) {
			ManagedLoggerRepository.logError(getClass()::getName, "Exception occurred", exc);
		}
	}

	String[] toDoubleQuotedStringsForStringsThatContainEmptySpace(String[] values) {
		String[] newValues = new String[values.length];
		for (int i = 0; i < values.length; i++) {
			newValues[i] = values[i].contains(" ") ? "\"" + values[i] + "\"" : values[i];
		}
		return newValues;
	}

	public static class Result implements Component {
		QueuedTaskExecutor.Task findingTask;
		Collection<FileSystemItem> resources;
		Collection<JavaClass> javaClasses;
		FileSystemItem store;

		Result() {
			this.javaClasses = new CopyOnWriteArrayList<>();
			this.resources = new CopyOnWriteArrayList<>();
		}

		public void putResource(FileSystemItem fileSystemItem) {
			resources.add(fileSystemItem);
		}

		JavaClass put(JavaClass javaClass) {
			javaClasses.add(javaClass);
			return javaClass;
		}

		public Collection<JavaClass> getJavaClasses() {
			return javaClasses;
		}

		public Collection<FileSystemItem> getResources() {
			return resources;
		}

		public JavaClass getJavaClass(Predicate<JavaClass> predicate) {
			return getJavaClasses().stream().filter(predicate).findFirst().orElseGet(() -> null);
		}

		public Collection<FileSystemItem> getResources(Predicate<FileSystemItem> predicate) {
			return getResources().stream().filter(predicate).collect(Collectors.toSet());
		}

		public Task getFindingTask() {
			return this.findingTask;
		}

		public void waitForTaskEnding() {
			findingTask.waitForFinish();
		}

		public FileSystemItem getStore() {
			return store;
		}

		@Override
		public void close() {
			findingTask.abort();
			findingTask = null;
			resources.clear();
			resources = null;
			javaClasses.clear();
			javaClasses = null;
			store = null;
		}
	}

	static class LazyHolder {
		static final Capturer CAPTURER_INSTANCE = Capturer.create(ComponentContainer.getInstance());

		static Capturer getCapturerInstance() {
			return CAPTURER_INSTANCE;
		}
	}
}
