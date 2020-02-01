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
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.burningwave.core.classes.ClassHelper;
import org.burningwave.core.classes.JavaClass;
import org.burningwave.core.classes.MemoryClassLoader;
import org.burningwave.core.common.Strings;
import org.burningwave.core.function.TriConsumer;
import org.burningwave.core.io.FileInputStream;
import org.burningwave.core.io.FileScanConfig;
import org.burningwave.core.io.FileSystemHelper;
import org.burningwave.core.io.FileSystemHelper.Scan;
import org.burningwave.core.io.FileSystemItem;
import org.burningwave.core.io.ZipInputStream;

public class Sniffer extends MemoryClassLoader {
	private ClassLoader mainClassLoader;
	private Consumer<JavaClass> javaClassAdder;
	private Consumer<FileSystemItem> resourceAdder;
	private Map<String, FileSystemItem> resources;
	private Map<String, JavaClass> javaClasses;
	private TriConsumer<String, String, ByteBuffer> resourcesConsumer;
	
	protected Sniffer(Collection<String> baseClassPaths,
		FileSystemHelper fileSystemHelper, ClassHelper classHelper, Consumer<JavaClass> javaClassAdder,
		Consumer<FileSystemItem> resourceAdder, TriConsumer<String, String, ByteBuffer> resourcesConsumer
	) {
		super(null, classHelper);
		this.javaClassAdder = javaClassAdder;
		this.resourceAdder = resourceAdder;
		this.resourcesConsumer = resourcesConsumer;
		this.resources = new ConcurrentHashMap<>();
		this.javaClasses = new ConcurrentHashMap<>();
		fileSystemHelper.scan(
			FileScanConfig.forPaths(baseClassPaths).toScanConfiguration(
				getFileSystemMapStorer(),
				getZipEntryMapStorer()
			)
		);
		this.mainClassLoader = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(this);
	}
	
    Consumer<Scan.ItemContext<FileInputStream>> getFileSystemMapStorer() {
		return (scannedItemContext) -> {
			String absolutePath = scannedItemContext.getInput().getAbsolutePath();
			resources.put(absolutePath, FileSystemItem.ofPath(absolutePath));
			if (absolutePath.endsWith(".class")) {
				JavaClass javaClass = JavaClass.create(scannedItemContext.getInput().toByteBuffer());
				addCompiledClass(javaClass.getName(), javaClass.getByteCode());
				javaClasses.put(absolutePath, javaClass);
			}
		};
	}    	
    	
	Consumer<Scan.ItemContext<ZipInputStream.Entry>> getZipEntryMapStorer() {
		return (scannedItemContext) -> {
			String absolutePath = scannedItemContext.getInput().getAbsolutePath();
			resources.put(absolutePath, FileSystemItem.ofPath(absolutePath));
			if (absolutePath.endsWith(".class")) {
				JavaClass javaClass = JavaClass.create(scannedItemContext.getInput().toByteBuffer());
				if (javaClass.getName().contains("Base64Variant")) {
					logDebug("Entered");
				}
				addCompiledClass(javaClass.getName(), javaClass.getByteCode());
				javaClasses.put(absolutePath, javaClass);
			}
		};
	}
	
	protected void consumeClass(String className) {
		for (Map.Entry<String, JavaClass> entry : javaClasses.entrySet()) {
			if (entry.getValue().getName().equals(className)) {
				JavaClass javaClass = entry.getValue();
				resourcesConsumer.accept(entry.getKey(), javaClass.getPath(), javaClass.getByteCode());
				javaClassAdder.accept(javaClass);
			}
		}
	}
	
	protected Collection<FileSystemItem> consumeResource(String relativePath, boolean breakWhenFound) {
		Set<FileSystemItem> founds = new LinkedHashSet<>();
		if (Strings.isNotEmpty(relativePath)) {
			for (Map.Entry<String, FileSystemItem> entry : resources.entrySet()) {
				if (entry.getValue().getAbsolutePath().endsWith(relativePath)) {
					FileSystemItem fileSystemItem = entry.getValue();
					founds.add(fileSystemItem);
					resourcesConsumer.accept(entry.getKey(), relativePath, fileSystemItem.toByteBuffer());
					resourceAdder.accept(fileSystemItem);
					if (breakWhenFound) {
						break;
					}
				}
			}
		}
		return founds;
	}
	
	@Override
	public void addLoadedCompiledClass(String className, ByteBuffer byteCode) {
		super.addLoadedCompiledClass(className, byteCode);
		consumeClass(className);
	};
	
	@Override
    protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
    	Class<?> cls = super.loadClass(className, resolve);
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
				resourceAdder.accept(fileSystemItem);
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
    		return getLoadedCompiledClassesAsInputStream(name);
    	}
    }
    
    @Override
    public void close() {
    	Thread.currentThread().setContextClassLoader(mainClassLoader);
    	mainClassLoader = null;
    	javaClassAdder = null;
    	resourceAdder = null;
    	super.close();
    }
}
