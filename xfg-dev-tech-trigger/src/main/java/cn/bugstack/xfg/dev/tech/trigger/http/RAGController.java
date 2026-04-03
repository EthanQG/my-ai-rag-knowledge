package cn.bugstack.xfg.dev.tech.trigger.http;

import cn.bugstack.xfg.dev.tech.api.IRAGService;
import cn.bugstack.xfg.dev.tech.api.response.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.PathResource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;

@Slf4j
@RestController()
@CrossOrigin("*")
@RequestMapping("/api/v1/rag/")
public class RAGController implements IRAGService {

    @Resource
    private OllamaChatClient ollamaChatClient;

    private TokenTextSplitter tokenTextSplitter = new TokenTextSplitter();
    @Resource
    private SimpleVectorStore simpleVectorStore;
    @Resource
    private PgVectorStore pgVectorStore;
    @Resource
    private RedissonClient redissonClient;

    @RequestMapping(value = "query_rag_tag_list", method = RequestMethod.GET)
    @Override
    public Response<List<String>> queryRagTagList() {
        RList<String> elements = redissonClient.getList("ragTag");
        return Response.<List<String>>builder()
                .code("0000")
                .info("调用成功")
                .data(elements)
                .build();
    }

    @RequestMapping(value = "file/upload", method = RequestMethod.POST, headers = "content-type=multipart/form-data")
    @Override
    public Response<String> uploadFile(@RequestParam String ragTag, @RequestParam("file") List<MultipartFile> files) {
        log.info("上传知识库开始 {}", ragTag);
        for (MultipartFile file : files) {
            TikaDocumentReader documentReader = new TikaDocumentReader(file.getResource());
            List<Document> documents = documentReader.get();
            List<Document> documentSplitterList = tokenTextSplitter.apply(documents);

            documents.forEach(doc -> doc.getMetadata().put("knowledge", ragTag));
            documentSplitterList.forEach(doc -> doc.getMetadata().put("knowledge", ragTag));

            pgVectorStore.accept(documentSplitterList);

            RList<String> elements = redissonClient.getList("ragTag");
            if (!elements.contains(ragTag)){
                elements.add(ragTag);
            }
        }

        log.info("上传知识库完成 {}", ragTag);
        return Response.<String>builder().code("0000").info("调用成功").build();
    }

    @RequestMapping(value = "analyze_git_repository", method = RequestMethod.POST)
    @Override
    public Response<String> analyzeGitRepository(@RequestParam String repoUrl, @RequestParam String userName, @RequestParam String token) throws Exception {
        //  优化 1：使用 UUID 拼接目录名，防止多线程并发请求时互相删除对方的文件
        String uniqueId = java.util.UUID.randomUUID().toString().replace("-", "");
        String localPath = "./git-cloned-repo-" + uniqueId;

        String repoProjectName = extractProjectName(repoUrl);
        log.info("克隆路径：{}", new File(localPath).getAbsolutePath());

        // 理论上带了 UUID 就不会有旧目录，但为了严谨还是保留这行
        FileUtils.deleteDirectory(new File(localPath));

        Git git = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(new File(localPath))
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(userName, token))
                .call();

        //  优化 2：定义需要过滤的后缀名集合（黑名单），防止无关的二进制文件污染大模型
        Set<String> ignoreExtensions = Set.of(
                ".png", ".jpg", ".jpeg", ".gif", ".ico", // 图片
                ".class", ".jar", ".war", ".ear",        // 编译产物
                ".zip", ".tar", ".gz", ".rar",           // 压缩包
                ".exe", ".dll", ".so", ".pdf"            // 其他二进制文件
        );

        Files.walkFileTree(Paths.get(localPath), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String filePath = file.toString();
                String fileName = file.getFileName().toString().toLowerCase();

                // 拦截 1：跳过 .git 隐藏目录下的所有 Git 历史和索引文件
                if (filePath.contains(File.separator + ".git" + File.separator)) {
                    return FileVisitResult.CONTINUE;
                }

                // 拦截 2：跳过黑名单里的无效二进制文件
                int lastDotIndex = fileName.lastIndexOf(".");
                if (lastDotIndex != -1) {
                    String extension = fileName.substring(lastDotIndex);
                    if (ignoreExtensions.contains(extension)) {
                        log.debug("跳过无效的二进制文件解析: {}", fileName);
                        return FileVisitResult.CONTINUE;
                    }
                }

                log.info("{} 遍历解析路径，上传知识库:{}", repoProjectName, file.getFileName());
                try {
                    TikaDocumentReader reader = new TikaDocumentReader(new PathResource(file));
                    List<Document> documents = reader.get();
                    // 如果文件是空的，直接跳过
                    if (documents == null || documents.isEmpty()) {
                        return FileVisitResult.CONTINUE;
                    }

                    List<Document> documentSplitterList = tokenTextSplitter.apply(documents);

                    documents.forEach(doc -> doc.getMetadata().put("knowledge", repoProjectName));
                    documentSplitterList.forEach(doc -> doc.getMetadata().put("knowledge", repoProjectName));

                    pgVectorStore.accept(documentSplitterList);
                } catch (Exception e) {
                    log.error("遍历解析路径，上传知识库失败:{}", file.getFileName(), e);
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                log.info("Failed to access file: {} - {}", file.toString(), exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        // 清理当前专属的临时目录
        FileUtils.deleteDirectory(new File(localPath));

        RList<String> elements = redissonClient.getList("ragTag");
        if (!elements.contains(repoProjectName)) {
            elements.add(repoProjectName);
        }

        git.close();

        log.info("遍历解析路径，上传完成:{}", repoUrl);

        return Response.<String>builder().code("0000").info("调用成功").build();
    }

    private String extractProjectName(String repoUrl) {
        String[] parts = repoUrl.split("/");
        String projectNameWithGit = parts[parts.length - 1];
        return projectNameWithGit.replace(".git", "");
    }

}
