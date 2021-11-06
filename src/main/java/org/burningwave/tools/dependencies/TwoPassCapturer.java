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
 * Copyright (c) 2020-2021 Roberto Gentili
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
import static org.burningwave.core.assembler.StaticComponentContainer.FileSystemHelper;
import static org.burningwave.core.assembler.StaticComponentContainer.ManagedLoggersRepository;
import static org.burningwave.core.assembler.StaticComponentContainer.Paths;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.burningwave.core.assembler.ComponentContainer;
import org.burningwave.core.assembler.ComponentSupplier;
import org.burningwave.core.classes.ByteCodeHunter;
import org.burningwave.core.classes.ClassCriteria;
import org.burningwave.core.classes.ClassPathHunter;
import org.burningwave.core.classes.JavaClass;
import org.burningwave.core.classes.SearchConfig;
import org.burningwave.core.function.ThrowingSupplier;
import org.burningwave.core.function.TriConsumer;
import org.burningwave.core.io.FileSystemItem;
import org.burningwave.core.io.PathHelper;

public class TwoPassCapturer extends Capturer {
	ClassPathHunter classPathHunter;
	PathHelper pathHelper;

	private TwoPassCapturer(PathHelper pathHelper, ByteCodeHunter byteCodeHunter, ClassPathHunter classPathHunter) {
		super(byteCodeHunter);
		this.pathHelper = pathHelper;
		this.classPathHunter = classPathHunter;
	}

	public static TwoPassCapturer create(ComponentSupplier componentSupplier) {
		return new TwoPassCapturer(componentSupplier.getPathHelper(), componentSupplier.getByteCodeHunter(),
				componentSupplier.getClassPathHunter());
	}

	public static TwoPassCapturer getInstance() {
		return LazyHolder.getCapturerInstance();
	}

	@Override
	public Result capture(String mainClassName, String[] mainMethodAruments, Collection<String> baseClassPaths,
			TriConsumer<String, String, ByteBuffer> resourceConsumer, boolean includeMainClass,
			Long continueToCaptureAfterSimulatorClassEndExecutionFor) {
		return capture(mainClassName, mainMethodAruments, baseClassPaths, resourceConsumer, includeMainClass,
				continueToCaptureAfterSimulatorClassEndExecutionFor, true);
	}

	private Result capture(String mainClassName, String[] mainMethodAruments, Collection<String> baseClassPaths,
			TriConsumer<String, String, ByteBuffer> resourceConsumer, boolean includeMainClass,
			Long continueToCaptureAfterSimulatorClassEndExecutionFor, boolean recursive) {
		final Result result = new Result(javaClass -> true, fileSystemItem -> true);
		result.findingTask = BackgroundExecutor.createTask(task -> {
			try (Sniffer resourceSniffer = new Sniffer(null).init(!recursive, baseClassPaths, result.javaClassFilter,
					result.resourceFilter, resourceConsumer)) {
				ThrowingSupplier<Class<?>, ClassNotFoundException> mainClassSupplier = recursive
						? () -> Class.forName(mainClassName, false, resourceSniffer)
						: () -> Class.forName(mainClassName);
				try {
					mainClassSupplier.get().getMethod("main", String[].class).invoke(null, (Object) mainMethodAruments);
					if (continueToCaptureAfterSimulatorClassEndExecutionFor != null
							&& continueToCaptureAfterSimulatorClassEndExecutionFor > 0) {
						Thread.sleep(continueToCaptureAfterSimulatorClassEndExecutionFor);
					}
				} catch (Throwable exc) {
					ManagedLoggersRepository.logError(getClass()::getName, "Exception occurred", exc);
					Driver.throwException(exc);
				}
			}
			if (recursive) {
				try {
					launchExternalCapturer(mainClassName, mainMethodAruments, result.getStore().getAbsolutePath(),
							baseClassPaths, includeMainClass, continueToCaptureAfterSimulatorClassEndExecutionFor);
				} catch (IOException | InterruptedException exc) {
					Driver.throwException(exc);
				}
			}
			if (recursive && !includeMainClass) {
				JavaClass mainJavaClass = result.getJavaClass(javaClass -> javaClass.getName().equals(mainClassName));
				Collection<FileSystemItem> mainJavaClassesFiles = result.getResources(
						fileSystemItem -> fileSystemItem.getAbsolutePath().endsWith(mainJavaClass.getPath()));
				FileSystemItem store = result.getStore();
				for (FileSystemItem fileSystemItem : mainJavaClassesFiles) {
					FileSystemHelper.delete(fileSystemItem.getAbsolutePath());
					fileSystemItem = fileSystemItem.getParent();
					while (fileSystemItem != null
							&& !fileSystemItem.getAbsolutePath().equals(store.getAbsolutePath())) {
						if (fileSystemItem.getChildren().isEmpty()) {
							FileSystemHelper.delete(fileSystemItem.getAbsolutePath());
						} else {
							break;
						}
						fileSystemItem = fileSystemItem.getParent();
					}
				}
			}
		}).submit();
		return result;
	}

	private Result captureAndStore(String mainClassName, String[] mainMethodAruments, Collection<String> baseClassPaths,
			String destinationPath, boolean includeMainClass, Long continueToCaptureAfterSimulatorClassEndExecutionFor,
			boolean recursive) {
		Result dependencies = capture(mainClassName, mainMethodAruments, baseClassPaths,
				getStoreFunction(destinationPath), includeMainClass,
				continueToCaptureAfterSimulatorClassEndExecutionFor, recursive);
		dependencies.store = FileSystemItem.ofPath(destinationPath);
		return dependencies;
	}

	private void launchExternalCapturer(String mainClassName, String[] mainMethodAruments, String destinationPath,
			Collection<String> baseClassPaths, boolean includeMainClass,
			Long continueToCaptureAfterSimulatorClassEndExecutionFor) throws IOException, InterruptedException {
		// Excluding Burningwave from next process classpath
		Set<String> classPaths = FileSystemItem.ofPath(destinationPath).refresh()
				.findInChildren(FileSystemItem.Criteria.forAllFileThat(fileSystemItem -> !fileSystemItem
						.getAbsolutePath().endsWith(BURNINGWAVE_CLASSES_RELATIVE_DESTINATION_PATH)
						&& !fileSystemItem.getAbsolutePath().endsWith(TOOLFACTORY_CLASSES_RELATIVE_DESTINATION_PATH)))
				.stream().map(child -> child.getAbsolutePath()).collect(Collectors.toSet());

		// Adding Burningwave to next process scanning path
		ClassPathHunter.SearchResult searchResult = classPathHunter
				.findBy(SearchConfig.forPaths(pathHelper.getMainClassPaths())
						.by(ClassCriteria.create()
								.packageName(packageName -> packageName.startsWith("io.github.toolfactory")
										|| packageName.startsWith("org.burningwave.jvm")
										|| packageName.startsWith("org.burningwave.core")
										|| packageName.startsWith("org.burningwave.tools"))));
		Collection<String> classPathsToBeScanned = new LinkedHashSet<>(baseClassPaths);
		classPathsToBeScanned.remove(destinationPath);
		Iterator<FileSystemItem> classPathIterator = searchResult.getClassPaths().iterator();
		while (classPathIterator.hasNext()) {
			FileSystemItem classPath = classPathIterator.next();
			if (!classPaths.contains(classPath.getAbsolutePath())) {
				classPaths.add(classPath.getAbsolutePath());
				// classPathsToBeScanned.remove(classPath.getAbsolutePath());
			}
		}
		ProcessBuilder processBuilder = System.getProperty("os.name").toLowerCase().contains("windows")
				? getProcessBuilderForWindows(classPaths, classPathsToBeScanned, mainClassName, mainMethodAruments,
						destinationPath, includeMainClass, continueToCaptureAfterSimulatorClassEndExecutionFor)
				: getProcessBuilderForUnix(classPaths, classPathsToBeScanned, mainClassName, mainMethodAruments,
						destinationPath, includeMainClass, continueToCaptureAfterSimulatorClassEndExecutionFor);
		Process process = processBuilder.start();
		process.waitFor();
	}

	private ProcessBuilder getProcessBuilderForWindows(Collection<String> classPaths,
			Collection<String> classPathsToBeScanned, String mainClassName, String[] mainMethodAruments,
			String destinationPath, boolean includeMainClass, Long continueToCaptureAfterSimulatorClassEndExecutionFor)
			throws IOException {
		String javaExecutablePath = System.getProperty("java.home") + "/bin/java";
		List<String> command = new LinkedList<>();
		command.add(Paths.clean(javaExecutablePath));
		command.add("-classpath");
		StringBuffer generatedClassPath = new StringBuffer();
		generatedClassPath.append("\"");
		if (!classPaths.isEmpty()) {
			generatedClassPath.append(String.join(System.getProperty("path.separator"), classPaths));
		}
		generatedClassPath.append("\"");
		command.add(generatedClassPath.toString());
		command.add(InternalLauncher.class.getName());
		command.add("\"" + String.join(System.getProperty("path.separator"), classPathsToBeScanned) + "\"");
		command.add(mainClassName);
		command.add("\"" + destinationPath + "\"");
		command.add(Boolean.valueOf(includeMainClass).toString());
		command.add(continueToCaptureAfterSimulatorClassEndExecutionFor.toString());
		command.addAll(Arrays.asList(mainMethodAruments));
		ProcessBuilder processBuilder = new ProcessBuilder(command);

		return processBuilder.inheritIO();
	}

	private ProcessBuilder getProcessBuilderForUnix(Collection<String> classPaths,
			Collection<String> classPathsToBeScanned, String mainClassName, String[] mainMethodAruments,
			String destinationPath, boolean includeMainClass, Long continueToCaptureAfterSimulatorClassEndExecutionFor)
			throws IOException {
		List<String> command = new LinkedList<>();
		String javaExecutablePath = Paths.clean(System.getProperty("java.home") + "/bin/java");
		command.add(javaExecutablePath);
		command.add("-classpath");
		StringBuffer generatedClassPath = new StringBuffer();
		if (!classPaths.isEmpty()) {
			generatedClassPath.append(String.join(System.getProperty("path.separator"), classPaths));
		}
		command.add(generatedClassPath.toString());
		command.add(InternalLauncher.class.getName());
		command.add(String.join(System.getProperty("path.separator"), classPathsToBeScanned));
		command.add(mainClassName);
		command.add(destinationPath);
		command.add(Boolean.valueOf(includeMainClass).toString());
		command.add(continueToCaptureAfterSimulatorClassEndExecutionFor.toString());
		command.addAll(Arrays.asList(mainMethodAruments));
		ProcessBuilder processBuilder = new ProcessBuilder(command);

		return processBuilder.inheritIO();
	}

	private void logReceivedParameters(String[] args, long wait, String fileSuffix) {
		try {

			String logs = String.join("\n\n", "classpath:\n\t" + String.join("\n\t",
					new TreeSet<>(Arrays.asList(
							System.getProperty("java.class.path").split(System.getProperty("path.separator"))))),
					"path to be scanned:\n\t" + String.join(System.getProperty("path.separator") + "\n\t",
							new TreeSet<>(Arrays.asList(args[0].split(System.getProperty("path.separator"))))),
					"mainClassName: " + args[1], "destinationPath: " + args[2], "includeMainClass: " + args[3],
					"continueToCaptureAfterSimulatorClassEndExecutionFor: " + args[4],
					(args.length > 5 ? "arguments: " + String.join(", ", Arrays.copyOfRange(args, 5, args.length))
							: ""));

			Files.write(java.nio.file.Paths.get(args[2] + "/params-" + fileSuffix + ".txt"), logs.getBytes());
			ManagedLoggersRepository.logDebug(() -> this.getClass().getName(), "\n\n" + logs + "\n\n");
			if (wait > 0) {
				Thread.sleep(wait);
			}
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}

	private static class InternalLauncher {

		@SuppressWarnings("unused")
		public static void main(String[] args) {
			String[] mainMethodArguments = args.length > 5 ? Arrays.copyOfRange(args, 5, args.length) : new String[0];
			TwoPassCapturer capturer = TwoPassCapturer.getInstance();
			try {
				Collection<String> paths = Arrays.asList(args[0].split(System.getProperty("path.separator")));
				String mainClassName = args[1];
				String destinationPath = args[2];
				boolean includeMainClass = Boolean.valueOf(args[3]);
				long continueToCaptureAfterSimulatorClassEndExecutionFor = Long.valueOf(args[4]);
				capturer.captureAndStore(mainClassName, mainMethodArguments, paths, destinationPath, includeMainClass,
						continueToCaptureAfterSimulatorClassEndExecutionFor, false).waitForTaskEnding();
			} catch (Throwable exc) {
				ManagedLoggersRepository.logError(() -> TwoPassCapturer.class.getName(), "Exception occurred", exc);
			} finally {
				String suffix = UUID.randomUUID().toString();
				capturer.logReceivedParameters(args, 0, suffix);
				capturer.createExecutor(args[2], args[1], mainMethodArguments, suffix);
			}
		}
	}

	private static class Result extends Capturer.Result {
		Function<JavaClass, Boolean> javaClassFilter;
		Function<FileSystemItem, Boolean> resourceFilter;

		Result(Function<JavaClass, Boolean> javaClassFilter, Function<FileSystemItem, Boolean> resourceFilter) {
			this.javaClassFilter = javaClassFilter;
			this.resourceFilter = resourceFilter;
			this.javaClasses = null;
			this.resources = null;
		}

		@Override
		public Collection<JavaClass> getJavaClasses() {
			if (javaClasses != null) {
				return javaClasses;
			} else {
				return loadResourcesAndJavaClasses().getValue();
			}
		}

		@Override
		public Collection<FileSystemItem> getResources() {
			if (resources != null) {
				return resources;
			} else {
				return loadResourcesAndJavaClasses().getKey();
			}
		}

		public Map.Entry<Collection<FileSystemItem>, Collection<JavaClass>> loadResourcesAndJavaClasses() {
			Map.Entry<Collection<FileSystemItem>, Collection<JavaClass>> itemsFound = null;
			if (this.findingTask.hasFinished()) {
				if (this.resources == null) {
					synchronized (this) {
						if (this.resources == null) {
							itemsFound = retrieveResources();
							this.resources = itemsFound.getKey();
							this.javaClasses = itemsFound.getValue();
							return itemsFound;
						}
					}
				}
			}
			return retrieveResources();
		}

		private Map.Entry<Collection<FileSystemItem>, Collection<JavaClass>> retrieveResources() {
			Collection<FileSystemItem> resources = ConcurrentHashMap.newKeySet();
			Collection<JavaClass> javaClasses = ConcurrentHashMap.newKeySet();
			Map.Entry<Collection<FileSystemItem>, Collection<JavaClass>> itemsFound = new AbstractMap.SimpleEntry<>(
					resources, javaClasses);
			for (FileSystemItem fileSystemItem : store.refresh().findInAllChildren(
					FileSystemItem.Criteria.forAllFileThat(fileSystemItem -> resourceFilter.apply(fileSystemItem)))) {
				resources.add(fileSystemItem);
				if ("class".equals(fileSystemItem.getExtension())) {
					javaClasses.add(JavaClass.create(fileSystemItem.toByteBuffer()));
				}
			}
			return itemsFound;
		}
	}

	private static class LazyHolder {
		private static final TwoPassCapturer CAPTURER_INSTANCE = TwoPassCapturer
				.create(ComponentContainer.getInstance());

		private static TwoPassCapturer getCapturerInstance() {
			return CAPTURER_INSTANCE;
		}
	}
}
