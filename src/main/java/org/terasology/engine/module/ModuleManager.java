/*
 * Copyright 2013 Moving Blocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.terasology.engine.module;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.asset.AssetSource;
import org.terasology.asset.sources.ArchiveSource;
import org.terasology.asset.sources.DirectorySource;
import org.terasology.engine.TerasologyConstants;
import org.terasology.engine.paths.PathManager;
import org.terasology.utilities.FilesUtil;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * This manager handles the available mods, which ones are active and access to their assets and code
 *
 * @author Immortius
 */
public class ModuleManager {

    public static final String ENGINE_PACKAGE = "engine";

    public static final String ASSETS_SUBDIRECTORY = "assets";
    public static final String OVERRIDES_SUBDIRECTORY = "overrides";

    private static final Logger logger = LoggerFactory.getLogger(ModuleManager.class);

    private Map<String, Module> mods = Maps.newHashMap();
    private URLClassLoader activeModClassLoader;
    private URLClassLoader allModClassLoader;

    private Reflections allReflections;
    private Reflections engineReflections;
    private Reflections activeModReflections;

    private ClassLoader[] engineClassLoaders;

    public ModuleManager() {
        this(ModuleManager.class.getClassLoader());
    }

    private ModuleManager(ClassLoader... engineClassLoaders) {
        this.engineClassLoaders = Arrays.copyOf(engineClassLoaders, engineClassLoaders.length);

        ConfigurationBuilder builder = new ConfigurationBuilder()
                .setScanners(new TypeAnnotationsScanner(), new SubTypesScanner());

        for (ClassLoader loader : engineClassLoaders) {
            builder.addClassLoader(loader)
                    .addUrls(ClasspathHelper.forPackage("org.terasology", loader));
        }
        engineReflections = new Reflections(builder);
        refresh();
    }

    public Reflections getEngineReflections() {
        return engineReflections;
    }

    public Reflections getActiveModReflections() {
        if (activeModReflections != null) {
            return activeModReflections;
        }
        return engineReflections;
    }

    /**
     * Provides the ability to reflect over the engine and all mods, not just active mods.  This should be used sparingly,
     * and classes retrieved from it should not be instantiated and used - this uses a different classloader than the
     * rest of the system.
     *
     * @return Reflections over the engine and all available mods
     */
    public Reflections getAllReflections() {
        if (allReflections == null) {
            List<URL> urls = Lists.newArrayList();
            for (Module module : getMods()) {
                if (module.isCodeMod()) {
                    urls.add(module.getModClasspathUrl());
                }
            }

            ConfigurationBuilder builder = new ConfigurationBuilder()
                    .addUrls(urls)
                    .addClassLoader(allModClassLoader);
            for (ClassLoader engineLoader : engineClassLoaders) {
                builder.addClassLoader(engineLoader)
                        .addUrls(ClasspathHelper.forPackage("org.terasology", engineLoader));
            }
            allReflections = new Reflections(builder);
            allReflections.merge(getEngineReflections());
            for (Module module : getMods()) {
                if (module.isCodeMod()) {
                    allReflections.merge(module.getReflections());
                }
            }
        }
        return allReflections;
    }

    /**
     * Rescans for mods.  This should not be done while a game is running, as it drops the module classloader.
     */
    public void refresh() {
        mods.clear();
        Gson gson = new Gson();
        for (Path rootModPath : PathManager.getInstance().getModPaths()) {

            // Directories first (they should override zips)
            try {
                for (Path modPath : Files.newDirectoryStream(rootModPath, FilesUtil.DIRECTORY_FILTER)) {
                    processModDirectory(modPath, gson);
                }
            } catch (IOException e) {
                logger.error("Failed to scan for directory mods", e);
            }

            // Zip files next
            try {
                for (Path modPath : Files.newDirectoryStream(rootModPath, new DirectoryStream.Filter<Path>() {
                    @Override
                    public boolean accept(Path entry) throws IOException {
                        return Files.isRegularFile(entry) && (entry.toString().endsWith(".jar") || entry.toString().endsWith(".zip"));
                    }
                })) {
                    processModArchive(modPath, gson);
                }
            } catch (IOException e) {
                logger.error("Failed to scan for jar and zip mods", e);
            }
        }
        List<URL> urls = Lists.newArrayList();
        for (Module module : getMods()) {
            if (module.isCodeMod()) {
                urls.add(module.getModClasspathUrl());
            }
        }
        if (allModClassLoader != null) {
            try {
                allModClassLoader.close();
            } catch (IOException e) {
                logger.error("Failed to cloase allModClassLoader", e);
            }
        }
        allModClassLoader = new URLClassLoader(urls.toArray(new URL[urls.size()]), getClass().getClassLoader());
        for (Module module : getMods()) {
            module.setInactiveClassLoader(allModClassLoader);
        }

        if (activeModClassLoader != null) {
            try {
                activeModClassLoader.close();
            } catch (IOException e) {
                logger.error("Failed to close activeModClassLoader", e);
            }
        }
        allReflections = null;
    }

    private void processModArchive(Path modPath, Gson gson) {
        try (ZipFile zipFile = new ZipFile(modPath.toFile())) {
            ZipEntry modInfoEntry = zipFile.getEntry("module.txt");
            if (modInfoEntry != null) {
                try {
                    ModuleInfo moduleInfo = gson.fromJson(new InputStreamReader(zipFile.getInputStream(modInfoEntry)), ModuleInfo.class);
                    if (!mods.containsKey(moduleInfo.getId().toLowerCase(Locale.ENGLISH))) {
                        ArchiveSource source = new ArchiveSource(moduleInfo.getId(), modPath.toFile(), ASSETS_SUBDIRECTORY, OVERRIDES_SUBDIRECTORY);
                        Module module = new Module(modPath, moduleInfo, source);
                        mods.put(moduleInfo.getId().toLowerCase(Locale.ENGLISH), module);
                        logger.info("Discovered module: {} (hasCode = {})", moduleInfo.getDisplayName(), module.isCodeMod());
                    } else {
                        logger.info("Discovered duplicate module: " + moduleInfo.getDisplayName() + ", skipping");
                    }
                } catch (FileNotFoundException | JsonIOException e) {
                    logger.warn("Failed to load module manifest for module at {}", modPath, e);
                }
            }
        } catch (IOException e) {
            logger.error("Invalid module file: {}", modPath, e);
        }
    }

    private void processModDirectory(Path modPath, Gson gson) throws IOException {
        Path modInfoFile = modPath.resolve("module.txt");
        if (Files.isRegularFile(modInfoFile)) {
            try (Reader reader = Files.newBufferedReader(modInfoFile, TerasologyConstants.CHARSET)) {
                ModuleInfo moduleInfo = gson.fromJson(reader, ModuleInfo.class);
                if (!mods.containsKey(moduleInfo.getId().toLowerCase(Locale.ENGLISH))) {
                    Path assetLocation = modPath.resolve(ASSETS_SUBDIRECTORY);
                    Path overridesLocation = modPath.resolve(OVERRIDES_SUBDIRECTORY);
                    AssetSource source = new DirectorySource(moduleInfo.getId(), assetLocation, overridesLocation);
                    Module module = new Module(modPath, moduleInfo, source);
                    mods.put(moduleInfo.getId().toLowerCase(Locale.ENGLISH), module);
                    logger.info("Discovered module: {} (hasCode = {})", moduleInfo.getDisplayName(), module.isCodeMod());
                } else {
                    logger.info("Discovered duplicate module: {}, skipping", moduleInfo.getDisplayName());
                }
            } catch (FileNotFoundException | JsonIOException e) {
                logger.warn("Failed to load module manifest for module at {}", modPath, e);
            }
        }
    }

    public void applyActiveMods() {
        List<URL> urls = Lists.newArrayList();
        for (Module module : getActiveMods()) {
            if (module.isCodeMod()) {
                urls.add(module.getModClasspathUrl());
            }
        }
        if (activeModClassLoader != null) {
            try {
                activeModClassLoader.close();
            } catch (IOException e) {
                logger.error("Failed to close activeModClassLoader", e);
            }
        }
        activeModClassLoader = new URLClassLoader(urls.toArray(new URL[urls.size()]), getClass().getClassLoader());
        for (Module module : getActiveMods()) {
            module.setActiveClassLoader(activeModClassLoader);
        }
        // We don't submit any urls as we don't want to scan (going to merge in from previous scans)
        activeModReflections = new Reflections(new ConfigurationBuilder().addClassLoader(getClass().getClassLoader()).addClassLoader(activeModClassLoader));
        activeModReflections.merge(getEngineReflections());
        for (Module module : getActiveMods()) {
            if (module.isCodeMod()) {
                activeModReflections.merge(module.getReflections());
            }
        }
    }

    public Collection<Module> getMods() {
        return mods.values();
    }

    public Module getMod(String modName) {
        return mods.get(modName.toLowerCase(Locale.ENGLISH));
    }

    public Iterable<Module> getActiveMods() {
        List<Module> activeModules = Lists.newArrayListWithCapacity(mods.size());
        for (Module module : mods.values()) {
            if (module.isEnabled()) {
                activeModules.add(module);
            }
        }
        return activeModules;
    }
}