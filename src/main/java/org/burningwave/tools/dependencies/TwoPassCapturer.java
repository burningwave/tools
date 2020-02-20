/*
 * This file is part of Burningwave Core.
 *
 * Author: Roberto Gentili
 *
 * Hosted at: https://github.com/burningwave/tools
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2020 Roberto Gentili
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.burningwave.ManagedLogger;
import org.burningwave.Throwables;
import org.burningwave.core.Strings;
import org.burningwave.core.assembler.ComponentContainer;
import org.burningwave.core.assembler.ComponentSupplier;
import org.burningwave.core.classes.ClassCriteria;
import org.burningwave.core.classes.ClassHelper;
import org.burningwave.core.classes.JavaClass;
import org.burningwave.core.classes.hunter.ByteCodeHunter;
import org.burningwave.core.classes.hunter.ClassPathHunter;
import org.burningwave.core.classes.hunter.SearchConfig;
import org.burningwave.core.function.ThrowingSupplier;
import org.burningwave.core.function.TriConsumer;
import org.burningwave.core.io.FileScanConfig;
import org.burningwave.core.io.FileSystemHelper;
import org.burningwave.core.io.FileSystemItem;
import org.burningwave.core.io.FileSystemScanner;
import org.burningwave.core.io.PathHelper;
import org.burningwave.core.jvm.LowLevelObjectsHandler;


public class TwoPassCapturer extends Capturer {
	ClassPathHunter classPathHunter;
	PathHelper pathHelper;
	private TwoPassCapturer(
		FileSystemScanner fileSystemScanner,
		PathHelper pathHelper,
		ByteCodeHunter byteCodeHunter,
		ClassPathHunter classPathHunter,
		ClassHelper classHelper,
		LowLevelObjectsHandler lowLevelObjectsHandler
	) {
		super(fileSystemScanner, byteCodeHunter, classHelper, lowLevelObjectsHandler);
		this.pathHelper = pathHelper;
		this.classPathHunter = classPathHunter;
	}
	
	public static TwoPassCapturer create(ComponentSupplier componentSupplier) {
		return new TwoPassCapturer(
			componentSupplier.getFileSystemScanner(),
			componentSupplier.getPathHelper(),
			componentSupplier.getByteCodeHunter(),
			componentSupplier.getClassPathHunter(),
			componentSupplier.getClassHelper(),
			componentSupplier.getLowLevelObjectsHandler()
		);
	}
	
	public static TwoPassCapturer getInstance() {
		return LazyHolder.getCapturerInstance();
	}
	
	@Override
	public Result capture(
		String mainClassName,
		Collection<String> baseClassPaths,
		TriConsumer<String, String, ByteBuffer> resourceConsumer,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor
	) {
		return capture(mainClassName, baseClassPaths, resourceConsumer, includeMainClass, continueToCaptureAfterSimulatorClassEndExecutionFor, true);
	}
	
	public Result capture(
		String mainClassName,
		Collection<String> baseClassPaths,
		TriConsumer<String, String, ByteBuffer> resourceConsumer,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor,
		boolean recursive
	) {
		lowLevelObjectsHandler.disableIllegalAccessLogger();
		final Result result = new Result(
			this.fileSystemScanner,
				javaClass -> true,
				fileSystemItem -> true
		);
		result.findingTask = CompletableFuture.runAsync(() -> {
			try (Sniffer resourceSniffer = new Sniffer(null).init(
					!recursive,
					fileSystemScanner,
					classHelper,
					baseClassPaths,
					result.javaClassFilter,
					result.resourceFilter,
					resourceConsumer
				)
			) {	
				ThrowingSupplier<Class<?>> mainClassSupplier = recursive ?
					() -> Class.forName(mainClassName, false, resourceSniffer):
					() -> Class.forName(mainClassName);
				try {
					mainClassSupplier.get().getMethod("main", String[].class).invoke(null, (Object)new String[]{});
					if (continueToCaptureAfterSimulatorClassEndExecutionFor != null && continueToCaptureAfterSimulatorClassEndExecutionFor > 0) {
						Thread.sleep(continueToCaptureAfterSimulatorClassEndExecutionFor);
					}
				} catch (Throwable exc) {
					logError("Exception occurred", exc);
					throw Throwables.toRuntimeException(exc);				
				}
			}
			if (recursive) {
				try {
					launchExternalCapturer(
						mainClassName, result.getStore().getAbsolutePath(), baseClassPaths, includeMainClass, continueToCaptureAfterSimulatorClassEndExecutionFor
					);
				} catch (IOException | InterruptedException exc) {
					throw Throwables.toRuntimeException(exc);
				}
			}
			if (recursive && !includeMainClass) {	
				JavaClass mainJavaClass = result.getJavaClass(javaClass -> javaClass.getName().equals(mainClassName));
				Collection<FileSystemItem> mainJavaClassesFiles =
					result.getResources(fileSystemItem -> 
						fileSystemItem.getAbsolutePath().endsWith(mainJavaClass.getPath())
					);
				FileSystemItem store = result.getStore();
				for (FileSystemItem fileSystemItem : mainJavaClassesFiles) {
					FileSystemHelper.delete(fileSystemItem.getAbsolutePath());
					fileSystemItem = fileSystemItem.getParent();
					while (fileSystemItem != null && !fileSystemItem.getAbsolutePath().equals(store.getAbsolutePath())) {
						if (fileSystemItem.getChildren().isEmpty()) {
							FileSystemHelper.delete(fileSystemItem.getAbsolutePath());
						} else {
							break;
						}
						fileSystemItem = fileSystemItem.getParent();
					}
				}
			}
			lowLevelObjectsHandler.enableIllegalAccessLogger();
		});
		return result;
	}
	
	private Result captureAndStore(
		String mainClassName,
		Collection<String> baseClassPaths,
		String destinationPath,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor,
		boolean recursive
	) {
		Result dependencies = capture(
			mainClassName,
			baseClassPaths,
			getStoreFunction(destinationPath),
			includeMainClass,
			continueToCaptureAfterSimulatorClassEndExecutionFor,
			recursive
		);
		dependencies.store = FileSystemItem.ofPath(destinationPath);
		return dependencies;
	}
	
	private void launchExternalCapturer(
		String mainClassName, String destinationPath, Collection<String> baseClassPaths, boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor
	) throws IOException, InterruptedException {
		String javaExecutablePath = System.getProperty("java.home") + "/bin/java";
		List<String> command = new LinkedList<String>();
        command.add(Strings.Paths.clean(javaExecutablePath));
        StringBuffer generatedClassPath = new StringBuffer("\"");
        Set<String> classPaths = FileSystemItem.ofPath(destinationPath).getChildren().stream().map(
        	child -> child.getAbsolutePath()
        ).collect(Collectors.toSet());
        generatedClassPath.append(String.join(System.getProperty("path.separator"), classPaths));
        //Adding Burningwave to classpath
        ClassPathHunter.SearchResult searchResult = classPathHunter.findBy(
			SearchConfig.forPaths(
				pathHelper.getMainClassPaths()
			).by(
				ClassCriteria.create().className(clsName -> 
					clsName.equals(this.getClass().getName()) || clsName.equals(ComponentSupplier.class.getName())
				)
			)
    	);
        Collection<String> classPathsToBeScanned = new LinkedHashSet<>(baseClassPaths);
        classPathsToBeScanned.remove(destinationPath);
        Iterator<FileSystemItem> classPathIterator = searchResult.getClassPaths().iterator();
        while (classPathIterator.hasNext()) {
        	FileSystemItem classPath = classPathIterator.next();
        	if (!generatedClassPath.toString().contains(classPath.getAbsolutePath())) {	
	        	generatedClassPath.append(
	        		System.getProperty("path.separator")
	            );
	        	generatedClassPath.append(
	        		classPath.getAbsolutePath()
	        	);
	        	classPathsToBeScanned.remove(classPath.getAbsolutePath());
        	}
        }
        generatedClassPath.append("\"");
        command.add("-classpath");
        command.add(generatedClassPath.toString());
        command.add(this.getClass().getName());
        String classPathsToBeScannedParam = "\"" + String.join(System.getProperty("path.separator"), classPathsToBeScanned);
        classPathsToBeScannedParam += "\"";
        command.add(classPathsToBeScannedParam);
        command.add(mainClassName);
        command.add("\"" + destinationPath + "\"");
        command.add(Boolean.valueOf(includeMainClass).toString());
        command.add(continueToCaptureAfterSimulatorClassEndExecutionFor.toString());
        ProcessBuilder processBuilder = new ProcessBuilder(command);

        Process process = processBuilder.inheritIO().start();
        
        process.waitFor();
	}
	
	public static void main(String[] args) throws ClassNotFoundException {
		try {
			Collection<String> paths = Arrays.asList(args[0].split(System.getProperty("path.separator")));
			String mainClassName = args[1];		
			String destinationPath = args[2];
			boolean includeMainClass = Boolean.valueOf(args[3]);
			long continueToCaptureAfterSimulatorClassEndExecutionFor = Long.valueOf(args[4]);
			TwoPassCapturer.getInstance().captureAndStore(
				mainClassName, paths, destinationPath, includeMainClass, continueToCaptureAfterSimulatorClassEndExecutionFor, false
			).waitForTaskEnding();
		} catch (Throwable exc) {
			ManagedLogger.Repository.getInstance().logError(TwoPassCapturer.class, "Exception occurred", exc);
		} finally {
			String suffix = UUID.randomUUID().toString();
			logReceivedParameters(args, 0, suffix);
			createExecutor(args[2], args[1], suffix);
		}
	}
	
	private static void logReceivedParameters(String[] args, long wait, String fileSuffix) {
		try {
			String logs =
					"classpath: " + System.getProperty("java.class.path") + "\n" +
					"path to be scanned: " + 
						String.join(";",
							Arrays.asList(
								args[0].split(System.getProperty("path.separator"))
							)
						) + "\n" +
					"mainClassName: " + args[1] + "\n" +
					"destinationPath: " + args[2] + "\n" +
					"includeMainClass: " + args[3] + "\n" +
					"continueToCaptureAfterSimulatorClassEndExecutionFor: " + args[4];
			
			Files.write(Paths.get(args[2] + "\\params-" + fileSuffix + ".txt"), logs.getBytes());
			ManagedLogger.Repository.getInstance().logDebug(TwoPassCapturer.class, "\n\n" + logs + "\n\n");
			if (wait > 0) {
				Thread.sleep(wait);
			}
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}
	
	private static class Result extends Capturer.Result {
		FileSystemScanner fileSystemScanner;
		Function<JavaClass, Boolean> javaClassFilter;
		Function<FileSystemItem, Boolean> resourceFilter;

		Result(
			FileSystemScanner fileSystemScanner,
			Function<JavaClass, Boolean> javaClassFilter,
			Function<FileSystemItem, Boolean> resourceFilter
		) {
			this.fileSystemScanner = fileSystemScanner;
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
			if (this.findingTask.isDone()) {
				if (this.resources == null) {
					synchronized (this.toString() + "_" + "resources") {
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
			Map.Entry<Collection<FileSystemItem>, Collection<JavaClass>> itemsFound = new 
				AbstractMap.SimpleEntry<>(resources, javaClasses);
			fileSystemScanner.scan(
				FileScanConfig.forPaths(
					store.getChildren().stream().filter(fileSystemItem ->
						fileSystemItem.isFolder()
					).map(fileSystemItem ->
						fileSystemItem.getAbsolutePath()
					).collect(
						Collectors.toSet()
					)
				).toScanConfiguration(
					FileSystemItem.getFilteredConsumerForFileSystemScanner(
						(fileSystemItem) -> resourceFilter.apply(fileSystemItem),
						(fileSystemItem) -> {
							resources.add(fileSystemItem);
							if (fileSystemItem.getExtension().equals("class")) {
								javaClasses.add(JavaClass.create(fileSystemItem.toByteBuffer()));
							}
						}
					)
				)
			);
			return itemsFound;
		}
	}
	
	private static class LazyHolder {
		private static final TwoPassCapturer CAPTURER_INSTANCE = TwoPassCapturer.create(ComponentContainer.getInstance());
		
		private static TwoPassCapturer getCapturerInstance() {
			return CAPTURER_INSTANCE;
		}
	}
}
