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
package org.burningwave.tools.jvm;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.function.Function;
import java.util.function.Supplier;

import org.burningwave.ManagedLogger;
import org.burningwave.Throwables;
import org.burningwave.core.assembler.ComponentSupplier;
import org.burningwave.core.classes.ClassFactory;
import org.burningwave.core.classes.ClassHelper;
import org.burningwave.core.classes.Classes;
import org.burningwave.core.classes.MemberFinder;
import org.burningwave.core.io.ByteBufferOutputStream;
import org.burningwave.core.io.StreamHelper;
import org.burningwave.core.io.Streams;
import org.burningwave.core.iterable.IterableObjectHelper;
import org.burningwave.core.jvm.JVMChecker;
import org.burningwave.tools.dependencies.Sniffer;

public class LowLevelObjectsHandler extends org.burningwave.core.jvm.LowLevelObjectsHandler {
	
	protected LowLevelObjectsHandler(
		JVMChecker jVMChecker,
		StreamHelper streamHelper,
		Supplier<ClassFactory> classFactorySupplier,
		Supplier<ClassHelper> classHelperSupplier,
		MemberFinder memberFinder,
		IterableObjectHelper iterableObjectHelper
	) {
		super(jVMChecker, streamHelper, classFactorySupplier, classHelperSupplier, memberFinder, iterableObjectHelper);
	}
	
	public static LowLevelObjectsHandler create(ComponentSupplier componentSupplier) {
		return new LowLevelObjectsHandler(
			componentSupplier.getJVMChecker(),
			componentSupplier.getStreamHelper(),
			() -> componentSupplier.getClassFactory(),
			() -> componentSupplier.getClassHelper(),
			componentSupplier.getMemberFinder(),
			componentSupplier.getIterableObjectHelper()
		);
	}
	
	public Function<Boolean, ClassLoader> setAsMasterClassLoader(ClassLoader classLoader) {
		ClassLoader masterClassLoader = getMasterClassLoader(Thread.currentThread().getContextClassLoader());
		return setAsParentClassLoader(masterClassLoader, classLoader, false);
	}
	
	@SuppressWarnings("restriction")
	public Function<Boolean, ClassLoader> setAsParentClassLoader(ClassLoader classLoader, ClassLoader futureParent, boolean mantainHierarchy) {
		Class<?> builtinClassLoaderClass = retrieveBuiltinClassLoaderClass();
		Field parentClassLoaderField = null;
		if (builtinClassLoaderClass != null && builtinClassLoaderClass.isAssignableFrom(classLoader.getClass())) {
			try (
				InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream("jdk/internal/loader/ClassLoaderDelegate.class");
				ByteBufferOutputStream bBOS = new ByteBufferOutputStream()
			) {
				Streams.copy(inputStream, bBOS);
				Class<?> classLoaderDelegateClass = getClassHelper().loadOrUploadClass(bBOS.toByteBuffer(), ClassLoader.getSystemClassLoader());
				Object classLoaderDelegate = LowLevelObjectsHandler.getUnsafe().allocateInstance(classLoaderDelegateClass);
				classLoaderDelegateClass.getDeclaredMethod("init", ClassLoader.class).invoke(classLoaderDelegate, futureParent);
				futureParent = (ClassLoader)classLoaderDelegate;
				parentClassLoaderField = getParentClassLoaderField(builtinClassLoaderClass);
			} catch (Throwable exc) {
				throw Throwables.toRuntimeException(exc);
			}
		} else {
			parentClassLoaderField = getParentClassLoaderField(ClassLoader.class);	
		}
		Long offset = LowLevelObjectsHandler.getUnsafe().objectFieldOffset(parentClassLoaderField);
		final ClassLoader exParent = (ClassLoader)LowLevelObjectsHandler.getUnsafe().getObject(classLoader, offset);
		LowLevelObjectsHandler.getUnsafe().putObject(classLoader, offset, futureParent);
		if (mantainHierarchy) {
			LowLevelObjectsHandler.getUnsafe().putObject(futureParent, offset, exParent);
		}
		return (reset) -> {
			if (reset) {
				LowLevelObjectsHandler.getUnsafe().putObject(classLoader, offset, exParent);
			}
			return exParent;
		};
	}

	public ClassLoader getMasterClassLoader(ClassLoader classLoader) {
		ClassLoader child = classLoader;
		while (child.getParent() != null) {
			child = child.getParent();
		}
		return child;
	}

	protected Field getParentClassLoaderField(Class<?> classLoaderClass)  {
		for (Field field : Classes.getDeclaredFields(classLoaderClass)) {
			if (field.getName().equals("parent")) {
				return field;
			}
		}
		return null;
	}

	public Class<?> retrieveBuiltinClassLoaderClass() {
		Class<?> builtinClassLoaderClass = null;
		try {
			builtinClassLoaderClass = Class.forName("jdk.internal.loader.BuiltinClassLoader");
		} catch (ClassNotFoundException e) {
			ManagedLogger.Repository.getInstance().logDebug(Sniffer.class, "jdk.internal.loader.BuiltinClassLoader doesn't exist");
		}
		return builtinClassLoaderClass;
	} 
	
}
