/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.gradle.conjure;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.palantir.gradle.conjure.api.ConjureExtension;
import com.palantir.gradle.conjure.api.ConjureProductDependenciesExtension;
import com.palantir.gradle.conjure.api.GeneratorOptions;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.Exec;
import org.gradle.plugins.ide.eclipse.EclipsePlugin;
import org.gradle.plugins.ide.idea.IdeaPlugin;
import org.gradle.plugins.ide.idea.model.IdeaModule;
import org.gradle.util.GFileUtils;
import org.gradle.util.GUtil;

public final class ConjurePlugin implements Plugin<Project> {
    private static final Logger log = Logging.getLogger(ConjurePlugin.class);

    static final String TASK_GROUP = "Conjure";
    static final String TASK_CLEAN = "clean";

    public static final String CONJURE_IR = "compileIr";

    private static final ImmutableSet<String> FIRST_CLASS_GENERATOR_PROJECT_NAMES =
            ImmutableSet.of("objects", "jersey", "retrofit", "undertow", "dialogue", "typescript", "python");

    // configuration names
    static final String CONJURE_COMPILER = "conjureCompiler";
    static final String CONJURE_TYPESCRIPT = "conjureTypeScript";
    static final String CONJURE_PYTHON = "conjurePython";
    static final String CONJURE_JAVA = "conjureJava";

    // executable distributions
    static final String CONJURE_COMPILER_BINARY = "com.palantir.conjure:conjure";
    static final String CONJURE_JAVA_BINARY = "com.palantir.conjure.java:conjure-java";
    static final String CONJURE_TYPESCRIPT_BINARY = "com.palantir.conjure.typescript:conjure-typescript@tgz";
    static final String CONJURE_PYTHON_BINARY = "com.palantir.conjure.python:conjure-python";

    // java project constants
    static final String JAVA_DIALOGUE_SUFFIX = "-dialogue";
    static final String JAVA_OBJECTS_SUFFIX = "-objects";
    static final String JAVA_JERSEY_SUFFIX = "-jersey";
    static final String JAVA_RETROFIT_SUFFIX = "-retrofit";
    static final String JAVA_UNDERTOW_SUFFIX = "-undertow";
    static final ImmutableSet<String> JAVA_PROJECT_SUFFIXES =
            ImmutableSet.of(JAVA_DIALOGUE_SUFFIX, JAVA_OBJECTS_SUFFIX, JAVA_JERSEY_SUFFIX, JAVA_RETROFIT_SUFFIX);
    static final String JAVA_GENERATED_SOURCE_DIRNAME = "src/generated/java";
    static final String JAVA_GITIGNORE_CONTENTS = "/src/generated/java/\n";

    static final String CONJURE_JAVA_LIB_DEP = "com.palantir.conjure.java:conjure-lib";

    /** Configuration where custom generators should be added as dependencies. */
    static final String CONJURE_GENERATORS_CONFIGURATION_NAME = "conjureGenerators";

    static final String CONJURE_GENERATOR_DEP_PREFIX = "conjure-";
    /** Make the old Java8 @Generated annotation available even when compiling with Java9+. */
    static final String ANNOTATION_API = "jakarta.annotation:jakarta.annotation-api:1.3.5";

    @Override
    public void apply(Project project) {
        project.getPlugins().apply(BasePlugin.class);
        ConjureExtension conjureExtension =
                project.getExtensions().create(ConjureExtension.EXTENSION_NAME, ConjureExtension.class);
        ConjureProductDependenciesExtension conjureProductDependenciesExtension = project.getExtensions()
                .create(ConjureProductDependenciesExtension.EXTENSION_NAME, ConjureProductDependenciesExtension.class);
        Configuration conjureGeneratorsConfiguration =
                project.getConfigurations().maybeCreate(CONJURE_GENERATORS_CONFIGURATION_NAME);

        // Set up conjure compile task
        Task compileConjure = project.getTasks().create("compileConjure", DefaultTask.class);
        compileConjure.setDescription("Generates code for your API definitions in src/main/conjure/**/*.yml");
        compileConjure.setGroup(TASK_GROUP);
        applyDependencyForIdeTasks(project, compileConjure);

        Copy copyConjureSourcesTask = getConjureSources(project);
        Task compileIrTask = createIrTasks(project, conjureProductDependenciesExtension, copyConjureSourcesTask);
        GenerateConjureServiceDependenciesTask productDependencyTask = project.getTasks()
                .create("generateConjureServiceDependencies", GenerateConjureServiceDependenciesTask.class, task -> {
                    task.setConjureServiceDependencies(conjureProductDependenciesExtension::getProductDependencies);
                });

        setupConjureJavaProject(
                project,
                immutableOptionsSupplier(conjureExtension::getJava),
                compileConjure,
                compileIrTask,
                conjureProductDependenciesExtension);
        setupConjurePythonProject(
                project, immutableOptionsSupplier(conjureExtension::getPython), compileConjure, compileIrTask);
        setupConjureTypescriptProject(
                project,
                immutableOptionsSupplier(conjureExtension::getTypescript),
                compileConjure,
                compileIrTask,
                productDependencyTask);
        setupGenericConjureProjects(
                project,
                conjureExtension::getGenericOptions,
                compileConjure,
                compileIrTask,
                conjureGeneratorsConfiguration);
    }

    private static void setupConjureJavaProject(
            Project project,
            Supplier<GeneratorOptions> optionsSupplier,
            Task compileConjure,
            Task compileIrTask,
            ConjureProductDependenciesExtension productDependencyExt) {
        if (JAVA_PROJECT_SUFFIXES.stream()
                .anyMatch(suffix -> project.findProject(project.getName() + suffix) != null)) {
            Configuration conjureJavaConfig = project.getConfigurations().maybeCreate(CONJURE_JAVA);
            File conjureJavaDir = new File(project.getBuildDir(), CONJURE_JAVA);
            project.getDependencies().add(CONJURE_JAVA, CONJURE_JAVA_BINARY);
            ExtractExecutableTask extractJavaTask = ExtractExecutableTask.createExtractTask(
                    project, "extractConjureJava", conjureJavaConfig, conjureJavaDir, "conjure-java");

            Task checkVersions = project.getTasks().create("checkConjureJavaVersions", CheckConjureJavaVersions.class);
            extractJavaTask.dependsOn(checkVersions);

            setupConjureObjectsProject(project, optionsSupplier, compileConjure, compileIrTask, extractJavaTask);
            setupConjureRetrofitProject(
                    project, optionsSupplier, compileConjure, compileIrTask, productDependencyExt, extractJavaTask);
            setupConjureJerseyProject(
                    project, optionsSupplier, compileConjure, compileIrTask, productDependencyExt, extractJavaTask);
            setupConjureUndertowProject(
                    project, optionsSupplier, compileConjure, compileIrTask, productDependencyExt, extractJavaTask);
            setupConjureDialogueProject(
                    project, optionsSupplier, compileConjure, compileIrTask, productDependencyExt, extractJavaTask);
        }
    }

    private static void setupConjureDialogueProject(
            Project project,
            Supplier<GeneratorOptions> optionsSupplier,
            Task compileConjure,
            Task compileIrTask,
            ConjureProductDependenciesExtension productDependencyExt,
            ExtractExecutableTask extractJavaTask) {
        String dialogueProjectName = project.getName() + JAVA_DIALOGUE_SUFFIX;
        if (project.findProject(dialogueProjectName) == null) {
            return;
        }
        String objectsProjectName = project.getName() + JAVA_OBJECTS_SUFFIX;
        if (project.findProject(objectsProjectName) == null) {
            throw new IllegalStateException(
                    String.format("Cannot enable '%s' without '%s'", dialogueProjectName, objectsProjectName));
        }

        project.project(dialogueProjectName, subproj -> {
            subproj.getPluginManager().apply(JavaLibraryPlugin.class);
            ignoreFromCheckUnusedDependencies(subproj);
            addGeneratedToMainSourceSet(subproj);
            project.getTasks().create("compileConjureDialogue", ConjureGeneratorTask.class, task -> {
                task.setDescription("Generates Dialogue client interfaces from your Conjure definitions.");
                task.setGroup(TASK_GROUP);
                task.setExecutablePath(extractJavaTask::getExecutable);
                task.setOptions(() -> optionsSupplier.get().addFlag("dialogue"));
                task.setOutputDirectory(subproj.file(JAVA_GENERATED_SOURCE_DIRNAME));
                task.setSource(compileIrTask);

                compileConjure.dependsOn(task);
                subproj.getTasks().getByName("compileJava").dependsOn(task);
                applyDependencyForIdeTasks(subproj, task);
                task.dependsOn(createWriteGitignoreTask(
                        subproj, "gitignoreConjureDialogue", subproj.getProjectDir(), JAVA_GITIGNORE_CONTENTS));
                task.dependsOn(extractJavaTask);
            });

            ConjureJavaServiceDependencies.configureJavaServiceDependencies(subproj, productDependencyExt);
            Task cleanTask = project.getTasks().findByName(TASK_CLEAN);
            cleanTask.dependsOn(project.getTasks().findByName("cleanCompileConjureDialogue"));
            subproj.getDependencies().add("api", project.findProject(objectsProjectName));
            subproj.getDependencies().add("api", "com.palantir.dialogue:dialogue-target");
        });
    }

    private static void setupConjureObjectsProject(
            Project project,
            Supplier<GeneratorOptions> optionsSupplier,
            Task compileConjure,
            Task compileIrTask,
            ExtractExecutableTask extractJavaTask) {
        String objectsProjectName = project.getName() + JAVA_OBJECTS_SUFFIX;
        if (project.findProject(objectsProjectName) != null) {
            project.project(objectsProjectName, subproj -> {
                subproj.getPluginManager().apply(JavaLibraryPlugin.class);
                ignoreFromCheckUnusedDependencies(subproj);
                addGeneratedToMainSourceSet(subproj);
                project.getTasks().create("compileConjureObjects", ConjureGeneratorTask.class, task -> {
                    task.setDescription("Generates Java POJOs from your Conjure definitions.");
                    task.setGroup(TASK_GROUP);
                    task.setExecutablePath(extractJavaTask::getExecutable);
                    task.setOptions(() -> optionsSupplier.get().addFlag("objects"));
                    task.setOutputDirectory(subproj.file(JAVA_GENERATED_SOURCE_DIRNAME));
                    task.setSource(compileIrTask);

                    compileConjure.dependsOn(task);
                    subproj.getTasks().getByName("compileJava").dependsOn(task);
                    applyDependencyForIdeTasks(subproj, task);
                    task.dependsOn(createWriteGitignoreTask(
                            subproj, "gitignoreConjureObjects", subproj.getProjectDir(), JAVA_GITIGNORE_CONTENTS));
                    task.dependsOn(extractJavaTask);
                });
                Task cleanTask = project.getTasks().findByName(TASK_CLEAN);
                cleanTask.dependsOn(project.getTasks().findByName("cleanCompileConjureObjects"));
                subproj.getDependencies().add("api", "com.palantir.conjure.java:conjure-lib");
            });
        }
    }

    private static void setupConjureRetrofitProject(
            Project project,
            Supplier<GeneratorOptions> optionsSupplier,
            Task compileConjure,
            Task compileIrTask,
            ConjureProductDependenciesExtension productDependencyExt,
            ExtractExecutableTask extractJavaTask) {
        String retrofitProjectName = project.getName() + JAVA_RETROFIT_SUFFIX;
        if (project.findProject(retrofitProjectName) == null) {
            return;
        }
        String objectsProjectName = project.getName() + JAVA_OBJECTS_SUFFIX;
        if (project.findProject(objectsProjectName) == null) {
            throw new IllegalStateException(
                    String.format("Cannot enable '%s' without '%s'", retrofitProjectName, objectsProjectName));
        }

        project.project(retrofitProjectName, subproj -> {
            subproj.getPluginManager().apply(JavaLibraryPlugin.class);

            ignoreFromCheckUnusedDependencies(subproj);
            addGeneratedToMainSourceSet(subproj);
            project.getTasks().create("compileConjureRetrofit", ConjureGeneratorTask.class, task -> {
                task.setDescription(
                        "Generates Retrofit interfaces for use on the client-side from your Conjure definitions.");
                task.setGroup(TASK_GROUP);
                task.setExecutablePath(extractJavaTask::getExecutable);
                task.setOptions(() -> optionsSupplier.get().addFlag("retrofit"));
                task.setOutputDirectory(subproj.file(JAVA_GENERATED_SOURCE_DIRNAME));
                task.setSource(compileIrTask);

                compileConjure.dependsOn(task);
                subproj.getTasks().getByName("compileJava").dependsOn(task);
                applyDependencyForIdeTasks(subproj, task);
                task.dependsOn(createWriteGitignoreTask(
                        subproj, "gitignoreConjureRetrofit", subproj.getProjectDir(), JAVA_GITIGNORE_CONTENTS));
                task.dependsOn(extractJavaTask);
            });

            ConjureJavaServiceDependencies.configureJavaServiceDependencies(subproj, productDependencyExt);
            Task cleanTask = project.getTasks().findByName(TASK_CLEAN);
            cleanTask.dependsOn(project.getTasks().findByName("cleanCompileConjureRetrofit"));
            subproj.getDependencies().add("api", project.findProject(objectsProjectName));
            subproj.getDependencies().add("api", "com.google.guava:guava");
            subproj.getDependencies().add("api", "com.squareup.retrofit2:retrofit");
            subproj.getDependencies().add("compileOnly", ANNOTATION_API);
        });
    }

    private static void setupConjureJerseyProject(
            Project project,
            Supplier<GeneratorOptions> optionsSupplier,
            Task compileConjure,
            Task compileIrTask,
            ConjureProductDependenciesExtension productDependencyExt,
            ExtractExecutableTask extractJavaTask) {
        String jerseyProjectName = project.getName() + JAVA_JERSEY_SUFFIX;
        if (project.findProject(jerseyProjectName) == null) {
            return;
        }

        String objectsProjectName = project.getName() + JAVA_OBJECTS_SUFFIX;
        if (project.findProject(objectsProjectName) == null) {
            throw new IllegalStateException(
                    String.format("Cannot enable '%s' without '%s'", jerseyProjectName, objectsProjectName));
        }

        project.project(jerseyProjectName, subproj -> {
            subproj.getPluginManager().apply(JavaLibraryPlugin.class);
            ignoreFromCheckUnusedDependencies(subproj);
            addGeneratedToMainSourceSet(subproj);
            project.getTasks().create("compileConjureJersey", ConjureGeneratorTask.class, task -> {
                task.setDescription("Generates Jersey interfaces from your Conjure definitions "
                        + "(for use on both the client-side and server-side).");
                task.setGroup(TASK_GROUP);
                task.setExecutablePath(extractJavaTask::getExecutable);
                task.setOptions(() -> optionsSupplier.get().addFlag("jersey"));
                task.setOutputDirectory(subproj.file(JAVA_GENERATED_SOURCE_DIRNAME));
                task.setSource(compileIrTask);

                compileConjure.dependsOn(task);
                subproj.getTasks().getByName("compileJava").dependsOn(task);
                applyDependencyForIdeTasks(subproj, task);
                task.dependsOn(createWriteGitignoreTask(
                        subproj, "gitignoreConjureJersey", subproj.getProjectDir(), JAVA_GITIGNORE_CONTENTS));
                task.dependsOn(extractJavaTask);
            });

            ConjureJavaServiceDependencies.configureJavaServiceDependencies(subproj, productDependencyExt);
            Task cleanTask = project.getTasks().findByName(TASK_CLEAN);
            cleanTask.dependsOn(project.getTasks().findByName("cleanCompileConjureJersey"));
            subproj.getDependencies().add("api", project.findProject(objectsProjectName));
            subproj.getDependencies().add("api", "jakarta.ws.rs:jakarta.ws.rs-api");
            subproj.getDependencies().add("compileOnly", ANNOTATION_API);
        });
    }

    private static void setupConjureUndertowProject(
            Project project,
            Supplier<GeneratorOptions> optionsSupplier,
            Task compileConjure,
            Task compileIrTask,
            ConjureProductDependenciesExtension productDependencyExt,
            ExtractExecutableTask extractJavaTask) {
        String undertowProjectName = project.getName() + JAVA_UNDERTOW_SUFFIX;
        if (project.findProject(undertowProjectName) != null) {
            String objectsProjectName = project.getName() + JAVA_OBJECTS_SUFFIX;
            if (project.findProject(objectsProjectName) == null) {
                throw new IllegalStateException(
                        String.format("Cannot enable '%s' without '%s'", undertowProjectName, objectsProjectName));
            }

            project.project(undertowProjectName, subproj -> {
                subproj.getPluginManager().apply(JavaLibraryPlugin.class);
                ignoreFromCheckUnusedDependencies(subproj);
                addGeneratedToMainSourceSet(subproj);
                project.getTasks().create("compileConjureUndertow", ConjureGeneratorTask.class, task -> {
                    task.setDescription(
                            "Generates Undertow server interfaces and handlers from your Conjure definitions.");
                    task.setGroup(TASK_GROUP);
                    task.setExecutablePath(extractJavaTask::getExecutable);
                    task.setOptions(() -> optionsSupplier.get().addFlag("undertow"));
                    task.setOutputDirectory(subproj.file(JAVA_GENERATED_SOURCE_DIRNAME));
                    task.setSource(compileIrTask);

                    compileConjure.dependsOn(task);
                    subproj.getTasks().getByName("compileJava").dependsOn(task);
                    applyDependencyForIdeTasks(subproj, task);
                    task.dependsOn(createWriteGitignoreTask(
                            subproj, "gitignoreConjureUndertow", subproj.getProjectDir(), JAVA_GITIGNORE_CONTENTS));
                    task.dependsOn(extractJavaTask);
                });

                ConjureJavaServiceDependencies.configureJavaServiceDependencies(subproj, productDependencyExt);
                Task cleanTask = project.getTasks().findByName(TASK_CLEAN);
                cleanTask.dependsOn(project.getTasks().findByName("cleanCompileConjureUndertow"));
                subproj.getDependencies().add("api", project.findProject(objectsProjectName));
                subproj.getDependencies().add("api", "com.palantir.conjure.java:conjure-undertow-lib");
            });
        }
    }

    @SuppressWarnings({"unchecked", "RawTypes"})
    private static void ignoreFromCheckUnusedDependencies(Project proj) {
        proj.getPlugins().withId("com.palantir.baseline-exact-dependencies", plugin -> {
            Class<? extends Task> checkUnusedDependenciesTask;
            try {
                ClassLoader baselineClassloader = plugin.getClass().getClassLoader();
                checkUnusedDependenciesTask = (Class<? extends Task>)
                        baselineClassloader.loadClass("com.palantir.baseline.tasks.CheckUnusedDependenciesTask");
            } catch (ClassNotFoundException e) {
                log.warn("Failed to ignore conjure-lib from baseline's checkUnusedDependencies", e);
                return;
            }

            proj.getTasks().withType(checkUnusedDependenciesTask, task -> {
                try {
                    Method ignoreMethod = task.getClass().getMethod("ignore", String.class, String.class);
                    List<String> conjureJavaLibComponents = Splitter.on(':').splitToList(CONJURE_JAVA_LIB_DEP);
                    ignoreMethod.invoke(task, conjureJavaLibComponents.get(0), conjureJavaLibComponents.get(1));
                    // also ignore guava since retrofit adds it...
                    ignoreMethod.invoke(task, "com.google.guava", "guava");
                } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                    log.warn("Failed to ignore conjure-lib from baseline's checkUnusedDependencies", e);
                }
            });
        });
    }

    private static void setupConjureTypescriptProject(
            Project project,
            Supplier<GeneratorOptions> options,
            Task compileConjure,
            Task compileIrTask,
            GenerateConjureServiceDependenciesTask productDependencyTask) {
        String typescriptProjectName = project.getName() + "-typescript";
        if (project.findProject(typescriptProjectName) != null) {
            Configuration conjureTypeScriptConfig = project.getConfigurations().maybeCreate(CONJURE_TYPESCRIPT);
            project.project(typescriptProjectName, subproj -> {
                applyDependencyForIdeTasks(subproj, compileConjure);
                File conjureTypescriptDir = new File(project.getBuildDir(), CONJURE_TYPESCRIPT);
                File srcDirectory = subproj.file("src");
                project.getDependencies().add("conjureTypeScript", CONJURE_TYPESCRIPT_BINARY);

                ExtractExecutableTask extractConjureTypeScriptTask = ExtractExecutableTask.createExtractTask(
                        project,
                        "extractConjureTypeScript",
                        conjureTypeScriptConfig,
                        conjureTypescriptDir,
                        "conjure-typescript");
                Task compileConjureTypeScript = project.getTasks()
                        .create("compileConjureTypeScript", CompileConjureTypeScriptTask.class, task -> {
                            task.setDescription("Generates TypeScript files and a package.json from your "
                                    + "Conjure definitions.");
                            task.setGroup(TASK_GROUP);
                            task.setSource(compileIrTask);
                            task.setExecutablePath(extractConjureTypeScriptTask::getExecutable);
                            task.setProductDependencyFile(productDependencyTask.getOutputFile());
                            task.setOutputDirectory(srcDirectory);
                            task.setOptions(options);
                            compileConjure.dependsOn(task);
                            task.dependsOn(createWriteGitignoreTask(
                                    subproj, "gitignoreConjureTypeScript", subproj.getProjectDir(), "/src/\n"));
                            task.dependsOn(extractConjureTypeScriptTask);
                            task.dependsOn(productDependencyTask);
                        });

                String npmCommand = OsUtils.NPM_COMMAND_NAME;
                Task installTypeScriptDependencies = project.getTasks()
                        .create("installTypeScriptDependencies", Exec.class, task -> {
                            task.commandLine(npmCommand, "install", "--no-package-lock", "--no-production");
                            task.workingDir(srcDirectory);
                            task.dependsOn(compileConjureTypeScript);
                            task.getInputs().file(new File(srcDirectory, "package.json"));
                            task.getOutputs().dir(new File(srcDirectory, "node_modules"));
                        });
                Task compileTypeScript = project.getTasks().create("compileTypeScript", Exec.class, task -> {
                    task.setDescription("Runs `npm tsc` to compile generated TypeScript files into JavaScript files.");
                    task.setGroup(TASK_GROUP);
                    task.commandLine(npmCommand, "run-script", "build");
                    task.workingDir(srcDirectory);
                    task.dependsOn(installTypeScriptDependencies);
                });
                Task publishTypeScript = project.getTasks().create("publishTypeScript", Exec.class, task -> {
                    task.setDescription("Runs `npm publish` to publish a TypeScript package "
                            + "generated from your Conjure definitions.");
                    task.setGroup(TASK_GROUP);
                    task.commandLine(npmCommand, "publish");
                    task.workingDir(srcDirectory);
                    task.dependsOn(compileConjureTypeScript);
                    task.dependsOn(compileTypeScript);
                });
                subproj.afterEvaluate(
                        p -> subproj.getTasks().maybeCreate("publish").dependsOn(publishTypeScript));
                Task cleanTask = project.getTasks().findByName(TASK_CLEAN);
                cleanTask.dependsOn(project.getTasks().findByName("cleanCompileConjureTypeScript"));
            });
        }
    }

    private static void setupConjurePythonProject(
            Project project, Supplier<GeneratorOptions> options, Task compileConjure, Task compileIrTask) {
        String pythonProjectName = project.getName() + "-python";
        if (project.findProject(pythonProjectName) != null) {
            Configuration conjurePythonConfig = project.getConfigurations().maybeCreate(CONJURE_PYTHON);

            project.project(pythonProjectName, subproj -> {
                applyDependencyForIdeTasks(subproj, compileConjure);
                File conjurePythonDir = new File(project.getBuildDir(), CONJURE_PYTHON);
                File buildDir = new File(project.getBuildDir(), "python");
                File distDir = new File(buildDir, "dist");
                project.getDependencies().add(CONJURE_PYTHON, CONJURE_PYTHON_BINARY);
                ExtractExecutableTask extractConjurePythonTask = ExtractExecutableTask.createExtractTask(
                        project, "extractConjurePython", conjurePythonConfig, conjurePythonDir, "conjure-python");
                Task compileConjurePython = project.getTasks()
                        .create("compileConjurePython", CompileConjurePythonTask.class, task -> {
                            task.setDescription("Generates Python files from your Conjure definitions.");
                            task.setGroup(TASK_GROUP);
                            task.setSource(compileIrTask);
                            task.setExecutablePath(extractConjurePythonTask::getExecutable);
                            task.setOutputDirectory(subproj.file("python"));
                            task.setOptions(options);
                            compileConjure.dependsOn(task);
                            task.dependsOn(createWriteGitignoreTask(
                                    subproj, "gitignoreConjurePython", subproj.getProjectDir(), "/python/\n"));
                            task.dependsOn(extractConjurePythonTask);
                        });
                project.getTasks().create("buildWheel", Exec.class, task -> {
                    task.setDescription("Runs `python setup.py sdist bdist_wheel --universal` to build a python wheel "
                            + "generated from your Conjure definitions.");
                    task.setGroup(TASK_GROUP);
                    task.commandLine(
                            "python",
                            "setup.py",
                            "build",
                            "--build-base",
                            buildDir,
                            "egg_info",
                            "--egg-base",
                            buildDir,
                            "sdist",
                            "--dist-dir",
                            distDir,
                            "bdist_wheel",
                            "--universal",
                            "--dist-dir",
                            distDir);
                    task.workingDir(subproj.file("python"));
                    task.dependsOn(compileConjurePython);
                    Task cleanTask = project.getTasks().findByName(TASK_CLEAN);
                    cleanTask.dependsOn(project.getTasks().findByName("cleanCompileConjurePython"));
                });
            });
        }
    }

    private static void setupGenericConjureProjects(
            Project project,
            Function<String, GeneratorOptions> getGenericOptions,
            Task compileConjure,
            Task compileIrTask,
            Configuration conjureGeneratorsConfiguration) {
        Map<String, Project> genericSubProjects = Maps.filterKeys(
                project.getChildProjects(),
                key -> !FIRST_CLASS_GENERATOR_PROJECT_NAMES.contains(
                        extractSubprojectLanguage(project.getName(), key)));
        if (genericSubProjects.isEmpty()) {
            return;
        }

        // Validating that each subproject has a corresponding generator.
        // We do this in afterEvaluate to ensure the configuration is populated.
        project.afterEvaluate(p -> {
            Map<String, Dependency> generators = conjureGeneratorsConfiguration.getAllDependencies().stream()
                    .collect(Collectors.toMap(
                            dependency -> {
                                Preconditions.checkState(
                                        dependency.getName().startsWith(CONJURE_GENERATOR_DEP_PREFIX),
                                        "Generators should start with '%s' according to conjure RFC 002, "
                                                + "but found name: '%s' (%s)",
                                        CONJURE_GENERATOR_DEP_PREFIX,
                                        dependency.getName(),
                                        dependency);
                                return dependency.getName().substring(CONJURE_GENERATOR_DEP_PREFIX.length());
                            },
                            Function.identity()));

            genericSubProjects.entrySet().forEach(entry -> {
                String subprojectName = entry.getKey();
                Project subproject = entry.getValue();
                String conjureLanguage = extractSubprojectLanguage(p.getName(), subprojectName);
                if (!FIRST_CLASS_GENERATOR_PROJECT_NAMES.contains(conjureLanguage)
                        && !generators.containsKey(conjureLanguage)) {
                    throw new RuntimeException(String.format(
                            "Discovered subproject %s without corresponding " + "generator dependency with name '%s'",
                            subproject.getPath(), ConjurePlugin.CONJURE_GENERATOR_DEP_PREFIX + subprojectName));
                }
            });
        });

        genericSubProjects.entrySet().forEach(e -> {
            String subprojectName = e.getKey();
            Project subproject = e.getValue();
            String conjureLanguage = extractSubprojectLanguage(project.getName(), subprojectName);

            // We create a lazy filtered FileCollection to avoid using afterEvaluate.
            FileCollection matchingGeneratorDeps = conjureGeneratorsConfiguration.fileCollection(
                    dep -> dep.getName().equals(CONJURE_GENERATOR_DEP_PREFIX + conjureLanguage));

            ExtractExecutableTask extractConjureGeneratorTask = ExtractExecutableTask.createExtractTask(
                    project,
                    GUtil.toLowerCamelCase("extractConjure " + conjureLanguage),
                    matchingGeneratorDeps,
                    new File(subproject.getBuildDir(), "generator"),
                    String.format("conjure-%s", conjureLanguage));

            String taskName = GUtil.toLowerCamelCase("compile conjure " + conjureLanguage);
            Task conjureLocalGenerateTask = project.getTasks().create(taskName, ConjureGeneratorTask.class, task -> {
                task.setDescription(String.format("Generates %s files from your Conjure definition.", conjureLanguage));
                task.setGroup(ConjurePlugin.TASK_GROUP);
                task.setSource(compileIrTask);
                task.setExecutablePath(extractConjureGeneratorTask::getExecutable);
                task.setOptions(() -> getGenericOptions.apply(conjureLanguage));
                task.setOutputDirectory(subproject.file("src"));
                task.dependsOn(extractConjureGeneratorTask);
            });
            compileConjure.dependsOn(conjureLocalGenerateTask);
        });
    }

    static void addGeneratedToMainSourceSet(Project subproj) {
        JavaPluginConvention javaPlugin = subproj.getConvention().findPlugin(JavaPluginConvention.class);
        javaPlugin.getSourceSets().getByName("main").getJava().srcDir(subproj.files(JAVA_GENERATED_SOURCE_DIRNAME));
    }

    static void applyDependencyForIdeTasks(Project project, Task compileConjure) {
        project.getPlugins().withType(IdeaPlugin.class, plugin -> {
            Task task = project.getTasks().findByName("ideaModule");
            if (task != null) {
                task.dependsOn(compileConjure);
            }

            IdeaModule module = plugin.getModel().getModule();

            // module.getSourceDirs / getGeneratedSourceDirs could be an immutable set, so defensively copy
            module.setSourceDirs(
                    mutableSetWithExtraEntry(module.getSourceDirs(), project.file(JAVA_GENERATED_SOURCE_DIRNAME)));

            module.setGeneratedSourceDirs(mutableSetWithExtraEntry(
                    module.getGeneratedSourceDirs(), project.file(JAVA_GENERATED_SOURCE_DIRNAME)));
        });
        project.getPlugins().withType(EclipsePlugin.class, plugin -> {
            Task task = project.getTasks().findByName("eclipseClasspath");
            if (task != null) {
                task.dependsOn(compileConjure);
            }
        });
    }

    private static <T> Set<T> mutableSetWithExtraEntry(Set<T> set, T extraItem) {
        Set<T> newSet = new LinkedHashSet<>(set);
        newSet.add(extraItem);
        return newSet;
    }

    static Task createWriteGitignoreTask(Project project, String taskName, File outputDir, String contents) {
        WriteGitignoreTask writeGitignoreTask = project.getTasks().create(taskName, WriteGitignoreTask.class);
        writeGitignoreTask.setOutputDirectory(outputDir);
        writeGitignoreTask.setContents(contents);
        return writeGitignoreTask;
    }

    private static Task createIrTasks(
            Project project, ConjureProductDependenciesExtension pdepsExtension, Copy copyConjureSourcesTask) {
        Configuration conjureCompilerConfig = project.getConfigurations().maybeCreate(CONJURE_COMPILER);
        File conjureCompilerDir = new File(project.getBuildDir(), CONJURE_COMPILER);
        project.getDependencies().add(CONJURE_COMPILER, CONJURE_COMPILER_BINARY);
        ExtractExecutableTask extractCompilerTask = ExtractExecutableTask.createExtractTask(
                project, "extractConjure", conjureCompilerConfig, conjureCompilerDir, "conjure");

        Provider<Directory> irDir = project.getLayout().getBuildDirectory().dir("conjure-ir");

        project.getTasks().register("rawIr", CompileIrTask.class, rawIr -> {
            rawIr.setInputDirectory(copyConjureSourcesTask::getDestinationDir);
            rawIr.setExecutableDir(extractCompilerTask::getOutputDirectory);
            rawIr.getOutputIrFile().set(irDir.map(dir -> dir.file("rawIr.conjure.json")));
            rawIr.dependsOn(copyConjureSourcesTask);
            rawIr.dependsOn(extractCompilerTask);
        });

        return project.getTasks().create(CONJURE_IR, CompileIrTask.class, compileIr -> {
            compileIr.setDescription("Converts your Conjure YML files into a single portable JSON file in IR format.");
            compileIr.setGroup(TASK_GROUP);
            compileIr.setInputDirectory(copyConjureSourcesTask::getDestinationDir);
            compileIr.setExecutableDir(extractCompilerTask::getOutputDirectory);
            compileIr.getOutputIrFile().set(irDir.map(dir -> dir.file(project.getName() + ".conjure.json")));
            compileIr.getProductDependencies().set(project.provider(pdepsExtension::getProductDependencies));
            compileIr.dependsOn(copyConjureSourcesTask);
            compileIr.dependsOn(extractCompilerTask);
        });
    }

    private static Copy getConjureSources(Project project) {
        // Conjure code source set
        SourceDirectorySet conjureSourceSet = project.getObjects().sourceDirectorySet("conjure", "conjure");
        conjureSourceSet.setSrcDirs(Collections.singleton("src/main/conjure"));
        conjureSourceSet.setIncludes(Collections.singleton("**/*.yml"));

        // Copy conjure imports into build directory
        File buildDir = new File(project.getBuildDir(), "conjure");

        // Copy conjure sources into build directory
        Copy copyConjureSourcesTask = project.getTasks().create("copyConjureSourcesIntoBuild", Copy.class);
        copyConjureSourcesTask.into(project.file(buildDir)).from(conjureSourceSet);

        // Replacing this with a lambda is not supported for build caching
        // (see https://github.com/gradle/gradle/issues/5510)
        copyConjureSourcesTask.doFirst(new Action<Task>() {
            @Override
            public void execute(Task _task) {
                GFileUtils.deleteDirectory(buildDir);
            }
        });

        Task cleanTask = project.getTasks().findByName(TASK_CLEAN);
        cleanTask.dependsOn(project.getTasks().findByName("cleanCopyConjureSourcesIntoBuild"));

        return copyConjureSourcesTask;
    }

    private static Supplier<GeneratorOptions> immutableOptionsSupplier(Supplier<GeneratorOptions> supplier) {
        return () -> new GeneratorOptions(supplier.get());
    }

    private static String extractSubprojectLanguage(String projectName, String subprojectName) {
        return subprojectName.substring(projectName.length() + 1);
    }
}
