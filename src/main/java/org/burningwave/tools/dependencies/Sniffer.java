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

import static org.burningwave.core.assembler.StaticComponentContainer.ClassLoaders;
import static org.burningwave.core.assembler.StaticComponentContainer.Classes;
import static org.burningwave.core.assembler.StaticComponentContainer.ManagedLoggersRepository;
import static org.burningwave.core.assembler.StaticComponentContainer.Strings;
import static org.burningwave.core.assembler.StaticComponentContainer.Synchronizer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.burningwave.core.classes.JavaClass;
import org.burningwave.core.classes.MemoryClassLoader;
import org.burningwave.core.function.ThrowingBiFunction;
import org.burningwave.core.function.TriConsumer;
import org.burningwave.core.io.FileSystemItem;


public class Sniffer extends MemoryClassLoader {
	private Function<JavaClass, Boolean> javaClassFilterAndAdder;
	private Function<FileSystemItem, Boolean> resourceFilterAndAdder;
	private Map<String, FileSystemItem> resources;
	//In this map the key is the absolute path
	private Map<String, JavaClass> javaClasses;
	//In this map the key is the class name
	private Map<String, JavaClass> bwJavaClasses;
	private TriConsumer<String, String, ByteBuffer> resourcesConsumer;
	ClassLoader threadContextClassLoader;
	Function<Boolean, ClassLoader> masterClassLoaderRetrieverAndResetter;
	ThrowingBiFunction<String, Boolean, Class<?>, ClassNotFoundException> classLoadingFunction;
	private Collection<String> baseClassPaths;
	public Sniffer(ClassLoader parent) {
		super(parent);
	}
	
	static {
        ClassLoader.registerAsParallelCapable();
    }
	
	protected  Sniffer init(boolean useAsMasterClassLoader,
		Collection<String> baseClassPaths,
		Function<JavaClass, Boolean> javaClassAdder,
		Function<FileSystemItem, Boolean> resourceAdder,
		TriConsumer<String, String, ByteBuffer> resourcesConsumer
	) {
		this.threadContextClassLoader = Thread.currentThread().getContextClassLoader();
		this.javaClassFilterAndAdder = javaClassAdder;
		this.resourceFilterAndAdder = resourceAdder;
		this.resourcesConsumer = resourcesConsumer;
		this.resources = new ConcurrentHashMap<>();
		this.javaClasses = new ConcurrentHashMap<>();
		this.bwJavaClasses = new ConcurrentHashMap<>();
		ManagedLoggersRepository.logInfo(getClass()::getName, "Scanning paths :\n{}",String.join("\n", baseClassPaths));
		this.baseClassPaths = baseClassPaths;
		if (useAsMasterClassLoader) {
			//Load in cache defineClass and definePackage methods for threadContextClassLoader
			ClassLoaders.getDefineClassMethod(threadContextClassLoader);
			ClassLoaders.getDefinePackageMethod(threadContextClassLoader);
			classLoadingFunction = (className, resolve) -> {
				if ((!className.startsWith("org.burningwave.") && 
					!className.startsWith("io.github.toolfactory."))
				) {
					return super.loadClass(className, resolve);
		    	} else {
		    		try {
						return ClassLoaders.defineOrLoad(threadContextClassLoader, bwJavaClasses.get(className));
					} catch (NoClassDefFoundError | ReflectiveOperationException exc) {
						throw new ClassNotFoundException(Classes.retrieveName(exc));
					}
		    	}
			};
			initResourceLoader();
			masterClassLoaderRetrieverAndResetter = ClassLoaders.setAsMaster(threadContextClassLoader, this);
		} else {
			classLoadingFunction = (clsName, resolveFlag) -> super.loadClass(clsName, resolveFlag);
			initResourceLoader();
			Thread.currentThread().setContextClassLoader(this);
		}
		
		return this;
	}
	
//	public Function<Boolean, ClassLoader> setAsMasterClassLoader(ClassLoader classLoader) {
//		ClassLoader masterClassLoader = getMasterClassLoader(Thread.currentThread().getContextClassLoader());
//		return ClassLoaders.setAsParent(masterClassLoader, classLoader);
//	}
//	
//	public ClassLoader getMasterClassLoader(ClassLoader classLoader) {
//		ClassLoader child = classLoader;
//		while (child.getParent() != null) {
//			child = child.getParent();
//		}
//		return child;
//	}
	
	@Override
	public synchronized void addByteCode(String className, ByteBuffer byteCode) {
		super.addByteCode(className, byteCode);
	}
 	
	
	private void initResourceLoader() {
		if (baseClassPaths != null) {
			Synchronizer.execute(getOperationId("initResourceLoader"), () ->{
				if (baseClassPaths != null) {
					this.resources = new ConcurrentHashMap<>();
					this.javaClasses = new ConcurrentHashMap<>();
					this.bwJavaClasses = new ConcurrentHashMap<>();
					ManagedLoggersRepository.logInfo(getClass()::getName, "Scanning paths :\n{}",String.join("\n", baseClassPaths));
					for (String classPath : baseClassPaths) {
						FileSystemItem.ofPath(classPath).refresh().findInAllChildren(
							FileSystemItem.Criteria.forAllFileThat((fileSystemItem) -> {								
								String absolutePath = fileSystemItem.getAbsolutePath();
								resources.put(absolutePath, FileSystemItem.ofPath(absolutePath));
								JavaClass javaClass = fileSystemItem.toJavaClass();
								if (javaClass != null) {
									addByteCode(javaClass.getName(), javaClass.getByteCode());
									javaClasses.put(absolutePath, javaClass);
									if (javaClass.getName().startsWith("org.burningwave.") ||
										javaClass.getName().startsWith("io.github.toolfactory.")
									) {
										bwJavaClasses.put(javaClass.getName(), javaClass);
									}
								}
								return true;
							})
						);
					}
					baseClassPaths = null;
				}
			});
		}
	}
    
	protected Collection<JavaClass> consumeClass(String className) {
		return consumeClasses(Arrays.asList(className));
	}
	
	public Collection<JavaClass> consumeClasses(Collection<String> currentNotFoundClasses) {
		Collection<JavaClass> javaClassesFound = new LinkedHashSet<>();
		for (Map.Entry<String, JavaClass> entry : javaClasses.entrySet()) {
			if (currentNotFoundClasses.contains(entry.getValue().getName())) {
				JavaClass javaClass = entry.getValue();
				if (javaClassFilterAndAdder.apply(javaClass)) {
					resourcesConsumer.accept(entry.getKey(), javaClass.getPath(), javaClass.getByteCode());
					javaClassesFound.add(javaClass);
				}
			}
		}
		return javaClassesFound;
	}
	
	protected Collection<FileSystemItem> consumeResource(String relativePath, boolean breakWhenFound) {
		Set<FileSystemItem> founds = new LinkedHashSet<>();
		if (Strings.isNotEmpty(relativePath)) {
			for (Map.Entry<String, FileSystemItem> entry : resources.entrySet()) {
				if (entry.getValue().getAbsolutePath().endsWith(relativePath)) {
					FileSystemItem fileSystemItem = entry.getValue();
					founds.add(fileSystemItem);
					if (resourceFilterAndAdder.apply(fileSystemItem)) {
						resourcesConsumer.accept(entry.getKey(), relativePath, fileSystemItem.toByteBuffer());
					}
					if (breakWhenFound) {
						break;
					}
				}
			}
		}
		return founds;
	}
	
	@Override
	public void addLoadedByteCode(String className, ByteBuffer byteCode) {
		super.addLoadedByteCode(className, byteCode);
		consumeClass(className);
	};
	
	@Override
    protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
		Class<?> cls = classLoadingFunction.apply(className, resolve);
    	consumeClass(className);
    	return cls;
    }
	
    public Class<?> _loadClass(String className, boolean resolve) throws ClassNotFoundException {
    	Class<?> cls = classLoadingFunction.apply(className, resolve);
    	consumeClass(className);
    	return cls;
    }
	
	@Override
	public URL getResource(String name) {
		Enumeration<URL> urls = getResources(name, true);
		if (urls.hasMoreElements()) {
			return urls.nextElement();
		}
		return null;
	}
	
	@Override
	public Enumeration<URL> getResources(String name) throws IOException {
		return getResources(name, false);
	}
    
	private Enumeration<URL> getResources(String name, boolean findFirst) {
		return Collections.enumeration(
			consumeResource(name, findFirst).stream().map(fileSystemItem -> {
				resourceFilterAndAdder.apply(fileSystemItem);
				return fileSystemItem.getURL();
			}
		).collect(Collectors.toSet()));
	}
	
    @Override
    public InputStream getResourceAsStream(String name) {
    	FileSystemItem fileSystemItem = consumeResource(name, true).stream().findFirst().orElseGet(() -> null);
    	if (fileSystemItem != null) {
    		return fileSystemItem.toInputStream();
    	} else {
    		return getByteCodeAsInputStream(name);
    	}
    }
    
    @Override
    public void close() {
    	closeResources(() -> javaClassFilterAndAdder == null, () -> {
	    	if (threadContextClassLoader != null) {
	    		Thread.currentThread().setContextClassLoader(threadContextClassLoader);
	    	}
	    	if (masterClassLoaderRetrieverAndResetter != null) {
	    		masterClassLoaderRetrieverAndResetter.apply(true);
	    	}
	    	resources.clear();
	    	//Nulling resources will cause crash
	    	//resources = null;
	    	javaClasses.clear();
	    	//Nulling javaClasses will cause crash
	    	//javaClasses = null;
//	    	javaClassFilterAndAdder = null;
//	    	resourceFilterAndAdder = null;
//	    	threadContextClassLoader = null;
//	    	classLoadingFunction = null;
//	    	clear();
	    	unregister();
    	});
    }
}
