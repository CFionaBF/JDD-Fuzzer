package cn.edu.fudan.utils;

import com.google.gson.Gson;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public abstract class FileUtils {

    private static int classNums = 0;
    // 解压过程中不做处理的文件夹列表
    private static String[] unDecompressDirList = {"META-INF", "OSGI-INF"};
    private static String[] specialDirList = {"/lib/", "WEB-INF/classes/"};
    private static HashSet<String> runtimeSpecialDirList = new HashSet<>();

    private static void checkFileRequirements(File source, File destination) throws FileNotFoundException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "target");
        if (!source.exists()) {
            throw new FileNotFoundException("Source '" + source + "' does not exist");
        }
    }

    public static void copyFile(File srcFile, File destFile) throws IOException {
        copyFile(srcFile, destFile, true);
    }

    public static void copyFile(File srcFile, File destFile, boolean preserveFileDate) throws IOException {
        checkFileRequirements(srcFile, destFile);
        if (srcFile.isDirectory()) {
            throw new IOException("Source '" + srcFile + "' exists but is a directory");
        } else if (srcFile.getCanonicalPath().equals(destFile.getCanonicalPath())) {
            throw new IOException("Source '" + srcFile + "' and destination '" + destFile + "' are the same");
        } else {
            File parentFile = destFile.getParentFile();
            if (parentFile != null && !parentFile.mkdirs() && !parentFile.isDirectory()) {
                throw new IOException("Destination '" + parentFile + "' directory cannot be created");
            } else if (destFile.exists() && !destFile.canWrite()) {
                throw new IOException("Destination '" + destFile + "' exists but is read-only");
            } else {
                doCopyFile(srcFile, destFile, preserveFileDate);
            }
        }
    }

    private static void doCopyFile(File srcFile, File destFile, boolean preserveFileDate) throws IOException {
        if (destFile.exists() && destFile.isDirectory()) {
            throw new IOException("Destination '" + destFile + "' exists but is a directory");
        } else {
            Path srcPath = srcFile.toPath();
            Path destPath = destFile.toPath();
            long newLastModifed = preserveFileDate ? srcFile.lastModified() : destFile.lastModified();
            Files.copy(srcPath, destPath, StandardCopyOption.REPLACE_EXISTING);
            checkEqualSizes(srcFile, destFile, Files.size(srcPath), Files.size(destPath));
            checkEqualSizes(srcFile, destFile, srcFile.length(), destFile.length());
            destFile.setLastModified(newLastModifed);
        }
    }

    private static void checkEqualSizes(File srcFile, File destFile, long srcLen, long dstLen) throws IOException {
        if (srcLen != dstLen) {
            throw new IOException("Failed to copy full contents from '" + srcFile + "' to '" + destFile + "' Expected length: " + srcLen + " Actual: " + dstLen);
        }
    }

    public static void cleanDirectory(File directory) throws IOException {
        File[] files = verifiedListFiles(directory);
        IOException exception = null;
        File[] var3 = files;
        int var4 = files.length;

        for (int var5 = 0; var5 < var4; ++var5) {
            File file = var3[var5];

            try {
                forceDelete(file);
            } catch (IOException var8) {
                exception = var8;
            }
        }

        if (null != exception) {
            throw exception;
        }
    }

    public static void forceDelete(File file) throws IOException {
        Counters.PathCounters deleteCounters;
        try {
            deleteCounters = delete(file.toPath());
        } catch (IOException var3) {
            throw new IOException("Unable to delete file: " + file, var3);
        }

        if (deleteCounters.getFileCounter().get() < 1L && deleteCounters.getDirectoryCounter().get() < 1L) {
            throw new FileNotFoundException("File does not exist: " + file);
        }
    }

    public static Counters.PathCounters delete(Path path) throws IOException {
        return Files.isDirectory(path, new LinkOption[0]) ? deleteDirectory(path) : deleteFile(path);
    }

    public static Counters.PathCounters deleteDirectory(Path directory) throws IOException {
        return ((DeletingPathVisitor)visitFileTree(DeletingPathVisitor.withLongCounters(), (Path)directory)).getPathCounters();
    }

    public static Counters.PathCounters deleteFile(Path file) throws IOException {
        if (Files.isDirectory(file, new LinkOption[0])) {
            throw new NotDirectoryException(file.toString());
        } else {
            Counters.PathCounters pathCounts = Counters.longPathCounters();
            long size = Files.exists(file, new LinkOption[0]) ? Files.size(file) : 0L;
            if (Files.deleteIfExists(file)) {
                pathCounts.getFileCounter().increment();
                pathCounts.getByteCounter().add(size);
            }

            return pathCounts;
        }
    }

    public static <T extends FileVisitor<? super Path>> T visitFileTree(T visitor, Path directory) throws IOException {
        Files.walkFileTree(directory, visitor);
        return visitor;
    }

    public static boolean isEmptyDirectory(File folder) {
        if (!folder.exists()) {
            return false;
        }
        if (Objects.requireNonNull(folder.listFiles()).length == 0) {
            return true;
        } else {
            return false;
        }
    }

    public static boolean isEmptyDirectory(Path directory) throws IOException {
        DirectoryStream<Path> directoryStream = Files.newDirectoryStream(directory);
        Throwable var2 = null;

        try {
            if (directoryStream.iterator().hasNext()) {
                boolean var3 = false;
                return var3;
            }
        } catch (Throwable var13) {
            var2 = var13;
            throw var13;
        } finally {
            if (directoryStream != null) {
                if (var2 != null) {
                    try {
                        directoryStream.close();
                    } catch (Throwable var12) {
                        var2.addSuppressed(var12);
                    }
                } else {
                    directoryStream.close();
                }
            }

        }

        return true;
    }

    public static boolean isSymlink(File file) {
        Objects.requireNonNull(file, "file");
        return Files.isSymbolicLink(file.toPath());
    }

    /**
     * 给定封装了该文件夹dir的File句柄，删除掉该文件夹下所有的空文件夹
     *
     * @param dirFile
     */
    public static void deleteEmptyUnderDir(File dirFile) throws IOException {

        if (!dirFile.isDirectory()) {
            throw new IllegalArgumentException("Parameter dirFile should be DIRECTORY!");
        }
        File[] filesUnderDir = dirFile.listFiles();
        // 长度为零就直接删除掉，因为已经确定是文件夹File
        // 因此不会出现为null的情况
        if (filesUnderDir.length == 0) {
            return;
        }
        // 如果长度不为零那就做递归处理
        for (File file : filesUnderDir) {
            if (file.isDirectory()) {
                deleteEmptyUnderDir(file);
            }
        }
        // 递归返回后再次判断当前目录下的文件
        if (FileUtils.isEmptyDirectory(dirFile)) {
            deleteDirectory(dirFile);
        }

    }

    public static void deleteDirectory(File directory) throws IOException {
        if (directory.exists()) {
            if (!isSymlink(directory)) {
                cleanDirectory(directory);
            }

            if (!directory.delete()) {
                String message = "Unable to delete directory " + directory + ".";
                throw new IOException(message);
            }
        }
    }

    public static List<File> getAllJarFiles(String dir) {

        File dirFile = new File(dir);
        if (!dirFile.isDirectory()) {
            throw new IllegalArgumentException(dir + " is not a directory!");
        }

        File[] tmpFiles = dirFile.listFiles();
        List<File> jarFiles = new ArrayList<>();
        List<File> dirFiles = new ArrayList<>();
        // 获取当前目录下的jar文件
        for (File file : tmpFiles) {
            // 对于目录作暂存处理
            if (file.isDirectory()) {
                dirFiles.add(file);
                continue;
            }
            String fileName = file.getName();
            if (!fileName.endsWith("jar") && !fileName.endsWith(".war")) {
                continue;
            }
            jarFiles.add(file);
        }
        List<File> files = new ArrayList<>(jarFiles);
        // 获取当前目录下的子目录中的jar文件
        for (File dFile : dirFiles) {
            List<File> tmpJarFiles = getAllJarFiles(dFile.getAbsolutePath());
            files.addAll(tmpJarFiles);
        }
        return files;
    }


    /**
     * 解压制定目录dir下的所有jar包到outPutDir/tmp/目录下
     *
     * @param dir 格式为/path/to/lib/dir/
     */
    public static void decompressJarFromDir(String dir) {

        // 解压dir的所有jar文件到dir/tmp/目录下
        try {
            // 创建临时文件夹
            String tmpDir = "tmp/";
            File tmpDirFile = new File(dir + tmpDir);
            if (tmpDirFile.exists()) {
                LogUtils.info2Log("有遗留的历史tmp文件夹，使用该tmp文件夹作为分析输入的class path。如果有新的项目要分析请删除该文件夹");
                return;
            }
            tmpDirFile.mkdirs();
            // 获取目录下所有的Jar文件
            List<File> jarFiles = getAllJarFiles(dir);
            for (File jarFile : jarFiles) {
                // 进行解压
                JarFile jar = new JarFile(jarFile);
                decompressJar(jar, dir + tmpDir);
            }
            LogUtils.info2Log("共解压" + classNums + "个类");
            // 对一些特殊目录做处理，比如WEB-INF/classes，移动到tmp目录下，使得这个文件夹下的类的class path正确
            for (String specialDir : runtimeSpecialDirList) {
                File srcDir = new File(specialDir);
                if (srcDir.isDirectory()) {
                    File[] filesUnderSpecialDir = srcDir.listFiles();
                    for (File file : filesUnderSpecialDir) {
                        String name = file.getName();
                        if (file.isDirectory()) {
                            copyDirectoryToDirectory(file, new File(dir + tmpDir));
                            deleteDirectory(file);
                        } else {
                            FileUtils.copyFile(file, new File(dir + tmpDir + name));
                            file.delete();
                        }
                    }
                }
            }
            // 删除掉解压出的空文件夹
            deleteEmptyUnderDir(tmpDirFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void copyDirectoryToDirectory(File sourceDir, File destinationDir) throws IOException {
        Objects.requireNonNull(sourceDir, "sourceDir");
        if (sourceDir.exists() && !sourceDir.isDirectory()) {
            throw new IllegalArgumentException("Source '" + sourceDir + "' is not a directory");
        } else {
            Objects.requireNonNull(destinationDir, "destinationDir");
            if (destinationDir.exists() && !destinationDir.isDirectory()) {
                throw new IllegalArgumentException("Destination '" + destinationDir + "' is not a directory");
            } else {
                copyDirectory(sourceDir, new File(destinationDir, sourceDir.getName()), true);
            }
        }
    }

    public static void copyDirectory(File srcDir, File destDir, boolean preserveFileDate) throws IOException {
        copyDirectory(srcDir, destDir, (FileFilter)null, preserveFileDate);
    }

    public static void copyDirectory(File srcDir, File destDir, FileFilter filter, boolean preserveFileDate) throws IOException {
        checkFileRequirements(srcDir, destDir);
        if (!srcDir.isDirectory()) {
            throw new IOException("Source '" + srcDir + "' exists but is not a directory");
        } else if (srcDir.getCanonicalPath().equals(destDir.getCanonicalPath())) {
            throw new IOException("Source '" + srcDir + "' and destination '" + destDir + "' are the same");
        } else {
            List<String> exclusionList = null;
            if (destDir.getCanonicalPath().startsWith(srcDir.getCanonicalPath())) {
                File[] srcFiles = filter == null ? srcDir.listFiles() : srcDir.listFiles(filter);
                if (srcFiles != null && srcFiles.length > 0) {
                    exclusionList = new ArrayList(srcFiles.length);
                    File[] var6 = srcFiles;
                    int var7 = srcFiles.length;

                    for(int var8 = 0; var8 < var7; ++var8) {
                        File srcFile = var6[var8];
                        File copiedFile = new File(destDir, srcFile.getName());
                        exclusionList.add(copiedFile.getCanonicalPath());
                    }
                }
            }

            doCopyDirectory(srcDir, destDir, filter, preserveFileDate, exclusionList);
        }
    }

    private static void doCopyDirectory(File srcDir, File destDir, FileFilter filter, boolean preserveFileDate, List<String> exclusionList) throws IOException {
        File[] srcFiles = filter == null ? srcDir.listFiles() : srcDir.listFiles(filter);
        if (srcFiles == null) {
            throw new IOException("Failed to list contents of " + srcDir);
        } else {
            if (destDir.exists()) {
                if (!destDir.isDirectory()) {
                    throw new IOException("Destination '" + destDir + "' exists but is not a directory");
                }
            } else if (!destDir.mkdirs() && !destDir.isDirectory()) {
                throw new IOException("Destination '" + destDir + "' directory cannot be created");
            }

            if (!destDir.canWrite()) {
                throw new IOException("Destination '" + destDir + "' cannot be written to");
            } else {
                File[] var6 = srcFiles;
                int var7 = srcFiles.length;

                for(int var8 = 0; var8 < var7; ++var8) {
                    File srcFile = var6[var8];
                    File dstFile = new File(destDir, srcFile.getName());
                    if (exclusionList == null || !exclusionList.contains(srcFile.getCanonicalPath())) {
                        if (srcFile.isDirectory()) {
                            doCopyDirectory(srcFile, dstFile, filter, preserveFileDate, exclusionList);
                        } else {
                            doCopyFile(srcFile, dstFile, preserveFileDate);
                        }
                    }
                }

                if (preserveFileDate) {
                    destDir.setLastModified(srcDir.lastModified());
                }

            }
        }
    }

    /**
     * 解压单个jar包的功能函数
     *
     * @param jarFile   被JarFile对象封装的Jar包
     * @param outputDir 解压的目录
     */
    public static void decompressJar(JarFile jarFile, String outputDir) throws IOException {

        if (jarFile == null || outputDir == null) {
            throw new IllegalArgumentException("Method parameters should not be null!");
        }
        if (outputDir.equals("")) {
            throw new IllegalArgumentException("Parameter outputDir should not be empty!");
        }

        // 获取jarFile中的每个文件并提取到outputDir下
        Enumeration<JarEntry> entries = jarFile.entries();
        List<String> newJarWarPaths = new ArrayList<>();
        while (entries.hasMoreElements()) {

            JarEntry entry = entries.nextElement();
            String entryName = entry.getName();
            String path = outputDir + entryName;
            File newFile = new File(path);
            // 不处理{ META-INF, OSGI-INF }文件夹下的内容
            boolean flag = false;
            for (String ignoreDirName : unDecompressDirList) {
                if (entryName.contains(ignoreDirName)) {
                    flag = true;
                    break;
                }
            }
            if (flag) {
                continue;
            }
            // 如果是一些包含类的特殊文件夹，那么就记录下来
            for (String specialDir : specialDirList) {
                if (path.endsWith(specialDir)) {
                    runtimeSpecialDirList.add(path);
                }
            }
            // 如果这个entry是一个文件夹且不存在，那么就新建它
            if (entry.isDirectory()) {
                if (!newFile.exists()) {
                    newFile.mkdirs();
                }
                continue;
            }
            // 如果是一个文件，那就判断它所处文件夹在不在。而后做解压处理
            // 如果是一个jar/war文件，那么就将其就地解压并记录下等待下一轮的处理
            boolean isJar = false;
            if (entryName.endsWith(".jar") || entryName.endsWith(".war") || entryName.endsWith(".properties")) {
                isJar = true;
                String[] splitedEntryName = entryName.split("/");
                String jarWarFileName = splitedEntryName[splitedEntryName.length - 1];
                path = outputDir + jarWarFileName;
                if (entryName.endsWith(".jar") || entryName.endsWith(".war")) newJarWarPaths.add(path);
                newFile = new File(path);
            }
            // 如果不是一个class文件，那么没有必要解压它
            if (!entryName.endsWith(".class") && !isJar) {
                continue;
            }
            File newFileParent = newFile.getParentFile();
            if (!newFileParent.exists()) {
                newFileParent.mkdirs();
            }
            InputStream inputStream;
            OutputStream outputStream;
            if (path.endsWith("/version.properties")) {
                String basePath = newFileParent.getAbsolutePath();
                basePath = basePath + "/clojure";
                newFileParent = new File(basePath);
                if (newFileParent.exists()) {
                    newFile = new File(basePath + "/version.properties");
                }
            }
            inputStream = jarFile.getInputStream(entry);
            outputStream = new BufferedOutputStream(new FileOutputStream(newFile));
            // 解压处理
            byte[] buffer = new byte[2048];
            int bytesNum = 0;
            while ((bytesNum = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, bytesNum);
            }
            outputStream.flush();
            outputStream.close();
            inputStream.close();
            classNums++;
        }
        // 处理当前fat jar中被新解压出来的jar/war包
        for (String newJarWarPath : newJarWarPaths) {
            JarFile newJarFile = new JarFile(newJarWarPath);
            decompressJar(newJarFile, outputDir);
            // 删除掉这些中间jar包
            //FileUtils.delete(new File(newJarWarPath));
            (new File(newJarWarPath)).delete();
        }
    }

    /**
     * 从dirPath加载json文件
     *
     * @param dirPath 文件夹路径
     * @return 所有文件夹下的json
     */
    public static HashSet<File> getJsonFilesFromDir(String dirPath) {
        HashSet<File> files = new HashSet<>();
        try {
            File file = new File(dirPath);
            if (file.isFile() & file.getName().endsWith("json")) {
                files.add(file);
            } else if (file.isDirectory()) {
                String[] fileList = file.list();
                for (int i = 0; i < fileList.length; i++) {
                    files.addAll(getJsonFilesFromDir(dirPath + File.separator + fileList[i]));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return files;
    }

    public static Object toGObject(String json, Class clazzType) {
        Gson gson = new Gson();
        return gson.fromJson(json, clazzType);
    }

    public static Object readFromJson(File file, Class clazzType) throws IOException {
        String content = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
        return toGObject(content, clazzType);
    }

    public static String readFileToString(File file, Charset charset) throws IOException {
        String jsonStr;
        FileReader fileReader = new FileReader(file);
        Reader reader = new InputStreamReader(new FileInputStream(file), charset);
        int ch = 0;
        StringBuilder sb = new StringBuilder();
        while ((ch = reader.read()) != -1) {
            sb.append((char) ch);
        }
        fileReader.close();
        reader.close();
        jsonStr = sb.toString();
        return jsonStr;
    }

    public static LinkedList<String> readFileContentToList(String fileName) {
        LinkedList<String> contentList = new LinkedList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(fileName))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("#") && !line.trim().isEmpty() && !line.equals("")) {
                    contentList.add(line);
                }
            }
        } catch (Exception e) {
            LogUtils.error("读取" + fileName + "时候报错: " + e.getMessage());
            System.exit(0);
        }

        return contentList;
    }

    public static void writeStringToFile(String fileName, String content) {
        File file = new File(fileName);
        try {
            ensureFileParentExist(file);
            writeStringToFile(file, content, "utf8");
        } catch (IOException e) {
            LogUtils.warn("In `FileUtils.writeStringToFile`: " + e.getMessage());
        }
    }

    public static void writeStringToFileAppend(String fileName, String content) {
        File file = new File(fileName);
        try {
            ensureFileParentExist(file);
            writeStringToFile(file, content, "utf8", true);
        } catch (IOException e) {
            LogUtils.warn("In `FileUtils.writeStringToFileAppend`: " + e.getMessage());
        }
    }

    public static void writeByteArrayToFile(String fileName, byte[] content) {
        File file = new File(fileName);
        try {
            ensureFileParentExist(file);
            writeByteArrayToFile(file, content);
        } catch (IOException e) {
            LogUtils.warn("In `FileUtils.writeByteArrayToFile`: " + e.getMessage());
        }
    }

    public static void deleteFileOrDirectory(String path) {
        deleteFileOrDirectory(new File(path));
    }

    public static void deleteFileOrDirectory(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            // 如果是目录，递归删除目录下的所有文件和子目录
            File[] files = fileOrDirectory.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteFileOrDirectory(child);
                }
            }
        }

        // 删除文件或空目录
        if (!fileOrDirectory.delete()) {
            LogUtils.error("Failed to delete: " + fileOrDirectory);
        }
    }

    private static File[] verifiedListFiles(File directory) throws IOException {
        String message;
        if (!directory.exists()) {
            message = directory + " does not exist";
            throw new IllegalArgumentException(message);
        } else if (!directory.isDirectory()) {
            message = directory + " is not a directory";
            throw new IllegalArgumentException(message);
        } else {
            File[] files = directory.listFiles();
            if (files == null) {
                throw new IOException("Failed to list contents of " + directory);
            } else {
                return files;
            }
        }
    }

    public static void forceMkdirParent(final File file) throws IOException {
        Objects.requireNonNull(file, "file");
        final File parent = getParentFile(file);
        if (parent == null) {
            return;
        }
        forceMkdir(parent);
    }

    /**
     * Gets the parent of the given file. The given file may be bull and a file's parent may as well be null.
     *
     * @param file The file to query.
     * @return The parent file or {@code null}.
     */
    private static File getParentFile(final File file) {
        return file == null ? null : file.getParentFile();
    }

    /**
     * Makes a directory, including any necessary but nonexistent parent
     * directories. If a file already exists with specified name but it is
     * not a directory then an IOException is thrown.
     * If the directory cannot be created (or the file already exists but is not a directory)
     * then an IOException is thrown.
     *
     * @param directory directory to create, must not be {@code null}.
     * @throws IOException if the directory was not created along with all its parent directories.
     * @throws IOException if the given file object is not a directory.
     * @throws SecurityException See {@link File#mkdirs()}.
     */
    public static void forceMkdir(final File directory) throws IOException {
        mkdirs(directory);
    }

    /**
     * Calls {@link File#mkdirs()} and throws an exception on failure.
     *
     * @param directory the receiver for {@code mkdirs()}, may be null.
     * @return the given file, may be null.
     * @throws IOException if the directory was not created along with all its parent directories.
     * @throws IOException if the given file object is not a directory.
     * @throws SecurityException See {@link File#mkdirs()}.
     * @see File#mkdirs()
     */
    private static File mkdirs(final File directory) throws IOException {
        if ((directory != null) && (!directory.mkdirs() && !directory.isDirectory())) {
            throw new IOException("Cannot create directory '" + directory + "'.");
        }
        return directory;
    }

     /**
     * make sure targetFile exists as a dir
     * 
     * @param targetFile
     */
    public static void ensureDirectoryExist(File targetFile) {
        try {
            if (targetFile == null) {
                LogUtils.warn("In `FileUtils.ensureDirectoryExist`: Target file is null, return");
                return;
            }
            if (!targetFile.exists()) {
                LogUtils.info2Log("In `FileUtils.ensureDirectoryExist`: Creating directory: " + targetFile.getAbsolutePath());
                FileUtils.forceMkdir(targetFile);
            } else {
                if (targetFile.isDirectory()) LogUtils.info2Log("In `FileUtils.ensureDirectoryExist`: Directory already exists: " + targetFile.getAbsolutePath());
                else {
                    LogUtils.error("In `FileUtils.ensureDirectoryExist`: Target file exists and is not a directory: " + targetFile.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            LogUtils.error("In `FileUtils.ensureDirectoryExist`: ensureDirectoryExist failed: " + e.toString());
            e.printStackTrace();
        }
    }
    
    /**
     * make sure file is exist
     * 
     * @param file
     */
    public static void ensureFileExist(File file) {
        try {
            if (file == null) {
                LogUtils.warn("Target file is null, return");
                return;
            }
            if (!file.exists()) {
                LogUtils.info2Log("Creating file: " + file.getAbsolutePath());
                ensureFileParentExist(file);
                file.createNewFile();
            }
        } catch (Exception e) {
            LogUtils.error("[-] ensureFileExist failed: " + e.toString());
            e.printStackTrace();
        }
    }

    /**
     * make sure the dir of targetFile exists
     * 
     */
    public static void ensureFileParentExist(File targetFile) throws IOException {
        try {
            if (targetFile == null) {
                LogUtils.warn("Target file is null, return");
                return;
            }
            File parentFile = targetFile.getParentFile();
            if (parentFile == null) {
                LogUtils.warn("Parent file" + targetFile.getAbsolutePath() + " is null, return");
                return;
            }
            if (parentFile != null && !targetFile.getParentFile().exists()) {
                FileUtils.forceMkdirParent(targetFile);
            }
        } catch (Exception e) {
            LogUtils.error("[-] ensureFileParentExist failed: " + e.toString());
            e.printStackTrace();
        }
        
    }
    
    public static class Counters {
        public Counters() {
        }

        public static Counters.Counter bigIntegerCounter() {
            return new Counters.BigIntegerCounter();
        }

        public static Counters.PathCounters bigIntegerPathCounters() {
            return new Counters.BigIntegerPathCounters();
        }

        public static Counters.Counter longCounter() {
            return new Counters.LongCounter();
        }

        public static Counters.PathCounters longPathCounters() {
            return new Counters.LongPathCounters();
        }

        public interface PathCounters {
            Counters.Counter getByteCounter();

            Counters.Counter getDirectoryCounter();

            Counters.Counter getFileCounter();
        }

        private static class LongPathCounters extends Counters.AbstractPathCounters {
            protected LongPathCounters() {
                super(Counters.longCounter(), Counters.longCounter(), Counters.longCounter());
            }
        }

        private static class LongCounter implements Counters.Counter {
            private long value;

            private LongCounter() {
            }

            public void add(long add) {
                this.value += add;
            }

            public boolean equals(Object obj) {
                if (this == obj) {
                    return true;
                } else if (!(obj instanceof Counters.Counter)) {
                    return false;
                } else {
                    Counters.Counter other = (Counters.Counter)obj;
                    return this.value == other.get();
                }
            }

            public long get() {
                return this.value;
            }

            public BigInteger getBigInteger() {
                return BigInteger.valueOf(this.value);
            }

            public Long getLong() {
                return this.value;
            }

            public int hashCode() {
                return Objects.hash(new Object[]{this.value});
            }

            public void increment() {
                ++this.value;
            }

            public String toString() {
                return Long.toString(this.value);
            }
        }

        public interface Counter {
            void add(long var1);

            long get();

            BigInteger getBigInteger();

            Long getLong();

            void increment();
        }

        private static class BigIntegerPathCounters extends Counters.AbstractPathCounters {
            protected BigIntegerPathCounters() {
                super(Counters.bigIntegerCounter(), Counters.bigIntegerCounter(), Counters.bigIntegerCounter());
            }
        }

        private static class BigIntegerCounter implements Counters.Counter {
            private BigInteger value;

            private BigIntegerCounter() {
                this.value = BigInteger.ZERO;
            }

            public void add(long val) {
                this.value = this.value.add(BigInteger.valueOf(val));
            }

            public boolean equals(Object obj) {
                if (this == obj) {
                    return true;
                } else if (!(obj instanceof Counters.Counter)) {
                    return false;
                } else {
                    Counters.Counter other = (Counters.Counter)obj;
                    return Objects.equals(this.value, other.getBigInteger());
                }
            }

            public long get() {
                return this.value.longValueExact();
            }

            public BigInteger getBigInteger() {
                return this.value;
            }

            public Long getLong() {
                return this.value.longValueExact();
            }

            public int hashCode() {
                return Objects.hash(new Object[]{this.value});
            }

            public void increment() {
                this.value = this.value.add(BigInteger.ONE);
            }

            public String toString() {
                return this.value.toString();
            }
        }

        private static class AbstractPathCounters implements Counters.PathCounters {
            private final Counters.Counter byteCounter;
            private final Counters.Counter directoryCounter;
            private final Counters.Counter fileCounter;

            protected AbstractPathCounters(Counters.Counter byteCounter, Counters.Counter directoryCounter, Counters.Counter fileCounter) {
                this.byteCounter = byteCounter;
                this.directoryCounter = directoryCounter;
                this.fileCounter = fileCounter;
            }

            public boolean equals(Object obj) {
                if (this == obj) {
                    return true;
                } else if (!(obj instanceof Counters.AbstractPathCounters)) {
                    return false;
                } else {
                    Counters.AbstractPathCounters other = (Counters.AbstractPathCounters)obj;
                    return Objects.equals(this.byteCounter, other.byteCounter) && Objects.equals(this.directoryCounter, other.directoryCounter) && Objects.equals(this.fileCounter, other.fileCounter);
                }
            }

            public Counters.Counter getByteCounter() {
                return this.byteCounter;
            }

            public Counters.Counter getDirectoryCounter() {
                return this.directoryCounter;
            }

            public Counters.Counter getFileCounter() {
                return this.fileCounter;
            }

            public int hashCode() {
                return Objects.hash(new Object[]{this.byteCounter, this.directoryCounter, this.fileCounter});
            }

            public String toString() {
                return String.format("%,d files, %,d directories, %,d bytes", this.fileCounter.get(), this.directoryCounter.get(), this.byteCounter.get());
            }
        }
    }

    public static void write(File file, CharSequence data, Charset charset, boolean append) throws IOException {
        String str = data == null ? null : data.toString();
        writeStringToFile(file, str, charset, append);
    }

    public static void write(File file, CharSequence data, String charsetName) throws IOException {
        write(file, data, charsetName, false);
    }

    public static void write(File file, CharSequence data, String charsetName, boolean append) throws IOException {
        write(file, data, toCharset(charsetName), append);
    }

    public static void write(String data, OutputStream output, Charset charset) throws IOException {
        if (data != null) {
            output.write(data.getBytes(toCharset(charset)));
        }
    }

    public static void writeByteArrayToFile(File file, byte[] data) throws IOException {
        writeByteArrayToFile(file, data, false);
    }

    public static void writeByteArrayToFile(File file, byte[] data, boolean append) throws IOException {
        writeByteArrayToFile(file, data, 0, data.length, append);
    }

    public static void writeByteArrayToFile(File file, byte[] data, int off, int len) throws IOException {
        writeByteArrayToFile(file, data, off, len, false);
    }

    public static void writeByteArrayToFile(File file, byte[] data, int off, int len, boolean append) throws IOException {
        OutputStream out = openOutputStream(file, append);
        Throwable var6 = null;

        try {
            out.write(data, off, len);
        } catch (Throwable var15) {
            var6 = var15;
            throw var15;
        } finally {
            if (out != null) {
                if (var6 != null) {
                    try {
                        out.close();
                    } catch (Throwable var14) {
                        var6.addSuppressed(var14);
                    }
                } else {
                    out.close();
                }
            }

        }

    }

    public static void writeStringToFile(File file, String data, Charset charset) throws IOException {
        writeStringToFile(file, data, charset, false);
    }

    public static void writeStringToFile(File file, String data, Charset charset, boolean append) throws IOException {
        OutputStream out = openOutputStream(file, append);
        Throwable var5 = null;

        try {
            write(data, out, charset);
        } catch (Throwable var14) {
            var5 = var14;
            throw var14;
        } finally {
            if (out != null) {
                if (var5 != null) {
                    try {
                        out.close();
                    } catch (Throwable var13) {
                        var5.addSuppressed(var13);
                    }
                } else {
                    out.close();
                }
            }

        }

    }

    public static void writeStringToFile(File file, String data, String charsetName) throws IOException {
        writeStringToFile(file, data, charsetName, false);
    }

    public static void writeStringToFile(File file, String data, String charsetName, boolean append) throws IOException {
        writeStringToFile(file, data, toCharset(charsetName), append);
    }

    public static FileOutputStream openOutputStream(File file) throws IOException {
        return openOutputStream(file, false);
    }

    public static FileOutputStream openOutputStream(File file, boolean append) throws IOException {
        if (file.exists()) {
            if (file.isDirectory()) {
                throw new IOException("File '" + file + "' exists but is a directory");
            }

            if (!file.canWrite()) {
                throw new IOException("File '" + file + "' cannot be written to");
            }
        } else {
            File parent = file.getParentFile();
            if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("Directory '" + parent + "' could not be created");
            }
        }

        return new FileOutputStream(file, append);
    }

    public static Charset toCharset(Charset charset) {
        return charset == null ? Charset.defaultCharset() : charset;
    }

    public static Charset toCharset(String charsetName) {
        return charsetName == null ? Charset.defaultCharset() : Charset.forName(charsetName);
    }

    public static abstract class SimplePathVisitor extends SimpleFileVisitor<Path> {
        protected SimplePathVisitor() {
        }
    }

    public static class CountingPathVisitor extends SimplePathVisitor {
        static final String[] EMPTY_STRING_ARRAY = new String[0];
        private final Counters.PathCounters pathCounters;

        public static CountingPathVisitor withBigIntegerCounters() {
            return new CountingPathVisitor(Counters.bigIntegerPathCounters());
        }

        public static CountingPathVisitor withLongCounters() {
            return new CountingPathVisitor(Counters.longPathCounters());
        }

        public CountingPathVisitor(Counters.PathCounters pathCounter) {
            this.pathCounters = (Counters.PathCounters)Objects.requireNonNull(pathCounter, "pathCounter");
        }

        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            } else if (!(obj instanceof CountingPathVisitor)) {
                return false;
            } else {
                CountingPathVisitor other = (CountingPathVisitor)obj;
                return Objects.equals(this.pathCounters, other.pathCounters);
            }
        }

        public Counters.PathCounters getPathCounters() {
            return this.pathCounters;
        }

        public int hashCode() {
            return Objects.hash(new Object[]{this.pathCounters});
        }

        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            this.pathCounters.getDirectoryCounter().increment();
            return FileVisitResult.CONTINUE;
        }

        public String toString() {
            return this.pathCounters.toString();
        }

        protected void updateFileCounters(Path file, BasicFileAttributes attributes) {
            this.pathCounters.getFileCounter().increment();
            this.pathCounters.getByteCounter().add(attributes.size());
        }

        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            if (Files.exists(file, new LinkOption[0])) {
                this.updateFileCounters(file, attributes);
            }

            return FileVisitResult.CONTINUE;
        }
    }

    public static class DeletingPathVisitor extends CountingPathVisitor {
        private final String[] skip;

        public static DeletingPathVisitor withBigIntegerCounters() {
            return new DeletingPathVisitor(Counters.bigIntegerPathCounters(), new String[0]);
        }

        public static DeletingPathVisitor withLongCounters() {
            return new DeletingPathVisitor(Counters.longPathCounters(), new String[0]);
        }

        public DeletingPathVisitor(Counters.PathCounters pathCounter, String... skip) {
            super(pathCounter);
            String[] temp = skip != null ? (String[])skip.clone() : EMPTY_STRING_ARRAY;
            Arrays.sort(temp);
            this.skip = temp;
        }

        private boolean accept(Path path) {
            return Arrays.binarySearch(this.skip, Objects.toString(path.getFileName(), (String)null)) < 0;
        }

        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            if (isEmptyDirectory(dir)) {
                Files.deleteIfExists(dir);
            }

            return super.postVisitDirectory(dir, exc);
        }

        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            super.preVisitDirectory(dir, attrs);
            return this.accept(dir) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
        }

        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            if (this.accept(file) && Files.exists(file, new LinkOption[0])) {
                Files.deleteIfExists(file);
            }

            this.updateFileCounters(file, attrs);
            return FileVisitResult.CONTINUE;
        }
    }
}
