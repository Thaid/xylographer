package com.iantorno.xylographer.publisher

import com.iantorno.xylographer.model.GitInfo
import com.iantorno.xylographer.model.ReleaseType
import org.ajoberstar.grgit.Commit
import org.ajoberstar.grgit.Grgit
import org.gradle.api.DefaultTask
import org.gradle.api.invocation.Gradle
import org.gradle.api.tasks.TaskAction

import javax.annotation.Nonnull
import java.util.regex.Matcher

/**
 *
 */
class PublishTask extends DefaultTask {

    static final String FILE_NAME = 'version.properties'
    static final String COMMAND_LINE_ARG = 'cut'
    static final String ANDROID_APP = 'android'
    static final String ANDROID_LIB = 'android-library'
    static final String SNAPSHOT_SUFFIX = "-SNAPSHOT"
    static final String DEFAULT_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss Z"
    static final String BAD_BRANCH_FORMAT = "FORMAT_ERR"

    @TaskAction
    void publish() {

        def cutOfficialRelease = project.hasProperty(COMMAND_LINE_ARG)

        project.configure(project) {
            def plugins = project.getPlugins()

            if (plugins.hasPlugin(ANDROID_APP) || plugins.hasPlugin(ANDROID_LIB)) {

                def versionFile = getOrInitializeVersioningFile(project.rootDir, FILE_NAME)

                String currentBuildIdentifier = getBuildIdentifierString(project.getGradle())

                ReleaseType releaseType = determineReleaseTypeFromIdString(currentBuildIdentifier)

                GitInfo info = readGitInfo()

                if (releaseType.equals(ReleaseType.VERSION_BUILD)) {
                    /*
                     * We always increment build number for test builds to distinguish versions during development
                     */
                    incrementProperty(versionFile, releaseType)
                } else if (!releaseType.equals(ReleaseType.VERSION_BUILD) && cutOfficialRelease) {
                    /*
                     * For MAJOR/MINOR/REVISION releases, we only want to increment once, when we ship.
                     * We use the -Pcut arg for this
                     */
                    incrementProperty(versionFile, releaseType)
                }

                String versionName = buildVersionName(versionFile, releaseType)
                int versionNumber = buildVersionNumber(versionFile)

                if (plugins.hasPlugin(ANDROID_APP)) {
                    String branchSuffix = getCurrentBranchSuffix(info.branch)
                    println("This has been identified as a non-library project, and will versioned accordingly...")

                    android.applicationVariants.all { variant ->
                        if (releaseType.equals(ReleaseType.VERSION_BUILD)) {
                            versionName = (versionName + "-" +branchSuffix)
                        }
                        variant.outputs.all {
                            setVersionCodeOverride(versionNumber)
                            setVersionNameOverride(versionName)
                        }
                    }
                } else if (plugins.hasPlugin(ANDROID_LIB)) {
                    println("This has been identified as a library project, and will versioned accordingly...")
                    android.libraryVariants.all { variant ->

                        if (releaseType.equals(ReleaseType.VERSION_BUILD)) {
                            versionName = (versionName + SNAPSHOT_SUFFIX)
                        }
                        variant.outputs.all {
                            setVersionCodeOverride(versionNumber)
                            setVersionNameOverride(versionName)
                        }
                    }
                }
            } else {
                println("Not an Android project or library...aborting versioning.")
            }
        }
    }

    /**
     * REGEX For branch naming:
     *
     * We want to match our JIRA ticketing pattern, ie: 'BANTA-111', BA-29, ICC-2034'...
     *
     * This regex will parse the string for any instance of a 2-5 capital letter identifier, paired with a number
     * from 1-99999, separated by a '-' character. This is unique for my job's purposes, but could be reformatted to
     * suit different needs.
     *
     *  ^ asserts position at start of the string
     *  Match a single character present in the list below [A-Z]{2,5}
     *  {2,5} Quantifier — Matches between 2 and 5 times, as many times as possible, giving back as needed (greedy)
     *  A-Z a single character in the range between A (index 65) and Z (index 90) (case sensitive)
     *  \- matches the character - literally (case sensitive)
     *  \d{1,5} matches a digit (equal to [0-9])
     *  {1,5} Quantifier — Matches between 1 and 5 times, as many times as possible, giving back as needed (greedy)
     * @param branchName {@link String} Branch name
     * @return {@link String} The shortened branch name to use in the version name.
     */
    static String getCurrentBranchSuffix(String branchName) {
        String parsedBranchId = BAD_BRANCH_FORMAT
        Matcher matcher = branchName =~ /^[A-Z]{2,5}\-\d{1,5}/
        if (matcher.size() > 0) {
            parsedBranchId = matcher[0]
        }
        println("Based on the passed in branch name ${branchName}, the resulting version suffix is ${parsedBranchId}")
        return parsedBranchId
    }

    /**
     * Generates a {@link GitInfo} object from the current git info.
     * @return {@link GitInfo}
     */
    GitInfo readGitInfo() {
        def missing = false
        def valid = true
        def branch
        def commit
        def committerDate
        try {
            println("SEARCHING FOR GIT DATA IN >> " + project.projectDir)
            Grgit grgit = Grgit.open(currentDir: project.projectDir)
            branch = grgit.branch.current().name
            Commit head = grgit.head()
            commit = head.abbreviatedId
            committerDate = head.date.format(DEFAULT_DATE_FORMAT)
        } catch (ignored) {
            println("Error >> " + ignored)
            missing = true
            branch = "unknown"
            commit = "unknown"
            committerDate = "unknown"
        }

        println("BRANCH NAME >> " + branch)
        println("COMMIT ID >> " + commit)
        println("COMMIT DATE >> " + committerDate)

        new GitInfo(missing: missing,
                valid: valid,
                branch: branch,
                commit: commit,
                committerDate: committerDate)
    }

    /**
     * Checks to see if a versioning file exists already, if it does, this method returns the reference to that
     * {@link File}, otherwise, it will create a new file, and initialize it with 0.0.0.0
     *
     * @param root_dir The root directory of the project, as {@link File}
     * @param filename The name of the versioning file {@link String}
     * @return {@link File}
     */
    File getOrInitializeVersioningFile(File root_dir, String filename) {
        File versionFile = new File(project.rootDir, 'version.properties')
        if (versionFile.exists()) {
            println "versioning file exists"
        } else {
            println "versioning file doesn't exist, creating as new"
            versionFile.createNewFile()
            initializeVersionFile(versionFile)
        }
        return versionFile
    }

    /**
     * Returns the {@link Gradle}.getStartParameter().getTaskRequests()
     * @param gradleInstance {@link Gradle}
     * @return {@link String} build task identifier
     */
    String getBuildIdentifierString(@Nonnull Gradle gradleInstance) {
        String taskReqStr = gradleInstance.getStartParameter().getTaskRequests().toString()
        println("Task identifier for this build task -> " + taskReqStr)
        return taskReqStr
    }

    /**
     * Parses the passed inidString for one of the {ReleaseType#mIdLabel} and returns the associated {@link ReleaseType}
     * @param idString The identifier from {@link Gradle}.getStartParameter().getTaskRequests()
     * @return The associated {@link ReleaseType}, or {@link ReleaseType#VERSION_BUILD} if no match is found
     */
    static ReleaseType determineReleaseTypeFromIdString(String idString) {
        ReleaseType returnType = ReleaseType.VERSION_BUILD
        if (idString != null) {
            if (idString.toLowerCase().contains(ReleaseType.VERSION_MAJOR.getIdentifyingLabel().toLowerCase())) {
                returnType = ReleaseType.VERSION_MAJOR
            } else if (idString.toLowerCase().contains(ReleaseType.VERSION_MINOR.getIdentifyingLabel().toLowerCase())) {
                returnType = ReleaseType.VERSION_MINOR
            } else if (idString.toLowerCase().contains(ReleaseType.VERSION_REVISION.getIdentifyingLabel().toLowerCase())) {
                returnType = ReleaseType.VERSION_REVISION
            }
        }
        println("Based on the passed in task id String {" + idString + "}, the rusulting Release type is -> " + returnType)
        return returnType
    }

    /**
     * Initializes version file with all 0 values for each release type.
     * @param file {@link File} to store properties in
     */
    void initializeVersionFile(@Nonnull File file) {
        for (ReleaseType type : ReleaseType.values()) {
            setProperty(file, type as String, 0)
        }
    }

    /**
     * Increments the given property by 1.
     * N.B. This will cascade and reset to 0, all lesser release types.
     *
     * @param propertiesFile {@link File} containing properties data
     * @param type {@link ReleaseType}
     */
    void incrementProperty(@Nonnull File propertiesFile, @Nonnull ReleaseType type) {
        Properties propertiesValues = loadVersionProperties(propertiesFile)
        println("Incrementing build property based on release type >> " + type as String)
        for (ReleaseType releaseType : ReleaseType.values().reverse()) {
            String property = propertiesValues.getProperty(releaseType as String)
            if (releaseType.equals(type)) {
                if (property == null) {
                    setProperty(propertiesFile, releaseType as String, 1)
                } else {
                    setProperty(propertiesFile, releaseType as String, property.toInteger() + 1)
                }
                break
            } else {
                setProperty(propertiesFile, releaseType as String, 0)
            }
        }
        println("Resulting versioning file >>")
        printCurrentProperties(loadVersionProperties(propertiesFile))
    }

    /**
     * Writes the given key value pair to the properties file.
     *
     * @param propertiesFile {@link File} containing the properties to edit
     * @param key {@link String} key to associate with value
     * @param value {@link Integer} value to store
     */
    void setProperty(@Nonnull File propertiesFile, @Nonnull String key, int value) {
        Properties propertiesValues = loadVersionProperties(propertiesFile)
        propertiesValues.setProperty(key, value.toString())
        propertiesValues.store(propertiesFile.newWriter(), null)
    }

    /**
     * Gets the given key value pair to the properties file.
     *
     * @param propertiesFile {@link File} containing the properties to edit
     * @param key {@link String} key to associate with value
     * @param value {@link Integer} value to store
     */
    int getProperty(@Nonnull File propertiesFile, @Nonnull String key) {
        Properties propertiesValues = loadVersionProperties(propertiesFile)
        def property = propertiesValues.getProperty(key, "0")
        return property.toInteger()
    }

    /**
     * Loads the 'version.properties' file for reading. Throws FileNotFoundException if no such
     * file exists.
     * @return {@link Properties} read from file.
     */
    Properties loadVersionProperties(File file) {
        if (!file.canRead()) {
            throw new FileNotFoundException("Could not read version.properties!")
        }
        Properties versionProps = new Properties()
        versionProps.load(new FileInputStream(file))
        return versionProps
    }

    /**
     * Dumps the current propertis to the console.
     * @param properties {@link Properties} to display in console
     */
    void printCurrentProperties(Properties properties) {
        properties.each { prop, val ->
            println(prop + ": " + val)
        }
    }

    /**
     * Builds an Android version code from the version of the project.
     * This is designed to handle the -SNAPSHOT and -RC format.
     *
     * I.e. during development the version ends with -SNAPSHOT. As the code stabilizes and release nears
     * one or many Release Candidates are tagged. These all end with "-RC1", "-RC2" etc.
     * And the final release is without any suffix.
     * @return
     */
    String buildVersionName(@Nonnull File propertiesFile, @Nonnull ReleaseType type) {
        def major = getProperty(propertiesFile, ReleaseType.VERSION_MAJOR as String)
        def minor = getProperty(propertiesFile, ReleaseType.VERSION_MINOR as String)
        def revision = getProperty(propertiesFile, ReleaseType.VERSION_REVISION as String)
        def build = getProperty(propertiesFile, ReleaseType.VERSION_BUILD as String)

        String versionName = major + "." + minor + "." + revision
        if (type.equals(ReleaseType.VERSION_BUILD)) {
            versionName += ("." + build)
        }
        println("Generated version name >> " + versionName)

        return versionName
    }

    int buildVersionNumber(@Nonnull File propertiesFile) {//, @Nonnull ReleaseType type) {
        def major = getProperty(propertiesFile, ReleaseType.VERSION_MAJOR as String)
        def minor = getProperty(propertiesFile, ReleaseType.VERSION_MINOR as String)
        def revision = getProperty(propertiesFile, ReleaseType.VERSION_REVISION as String)
        def build = getProperty(propertiesFile, ReleaseType.VERSION_BUILD as String)

        int versionNumber = ((major * 10000000) + (minor * 100000) + (revision * 1000) + (build * 1))
        println("Generated version number >> " + versionNumber)

        return versionNumber
    }
}


