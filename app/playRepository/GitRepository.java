/**
 * Yobi, Project Hosting SW
 *
 * Copyright 2012 NAVER Corp.
 * http://yobi.io
 *
 * @Author Ahn Hyeok Jun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package playRepository;

import controllers.ProjectApp;
import controllers.UserApp;
import controllers.routes;
import models.Project;
import models.PullRequest;
import models.User;
import models.enumeration.ResourceType;
import models.resource.Resource;
import models.support.ModelLock;
import org.apache.commons.collections.IteratorUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.node.ObjectNode;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.*;
import org.eclipse.jgit.diff.Edit.Type;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.jgit.util.io.NullOutputStream;
import org.tmatesoft.svn.core.SVNException;
import play.Logger;
import play.libs.Json;
import utils.FileUtil;
import utils.GravatarUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

import static org.eclipse.jgit.diff.DiffEntry.ChangeType.*;

public class GitRepository implements PlayRepository {
    private static final ModelLock<Project> PROJECT_LOCK = new ModelLock<>();

    public static final int DIFF_SIZE_LIMIT = 3 * FileDiff.SIZE_LIMIT;
    public static final int DIFF_LINE_LIMIT = 3 * FileDiff.LINE_LIMIT;
    public static final int DIFF_FILE_LIMIT = 2000;
    public static final int COMMIT_HISTORY_LIMIT = 1000 * 1000;

    /**
     * The base directory for Git repository
     */
    private static String repoPrefix = "repo/git/";

    /**
     * The base directory for Git merging-repository
     */
    private static String repoForMergingPrefix = "repo/git-merging/";

    public static String getRepoPrefix() {
        return repoPrefix;
    }

    public static void setRepoPrefix(String repoPrefix) {
        GitRepository.repoPrefix = repoPrefix;
    }

    public static String getRepoForMergingPrefix() {
        return repoForMergingPrefix;
    }

    public static void setRepoForMergingPrefix(String repoForMergingPrefix) {
        GitRepository.repoForMergingPrefix = repoForMergingPrefix;
    }

    private final Repository repository;
    private final String ownerName;
    private final String projectName;

    /**
     * 매개변수로 전달받은 {@code ownerName}과 {@code projectName}을 사용하여 Git 저장소를 참조할 {@link GitRepository}를 생성한다.
     *
     * @param ownerName
     * @param projectName
     * @throws IOException
     * @see #buildGitRepository(String, String, boolean)
     */
    public GitRepository(String ownerName, String projectName, boolean alternatesMergeRepo) {
        this.ownerName = ownerName;
        this.projectName = projectName;
        this.repository = buildGitRepository(ownerName, projectName, alternatesMergeRepo);
    }

    public GitRepository(String ownerName, String projectName) {
        this(ownerName, projectName, true);
    }

    /**
     * {@code project} 정보를 사용하여 Git 저장소를 참조할 {@link GitRepository}를 생성한다.
     *
     * @param project
     * @throws IOException
     * @see #GitRepository(String, String)
     */
    public GitRepository(Project project) throws IOException {
        this(project.owner, project.name, true);
    }

    /**
     * {@code ownerName}과 {@code projectName}을 받아서 {@link Repository} 객체를 생성한다.
     *
     * 실제 저장소를 생성하는건 아니고, Git 저장소를 참조할 수 있는 {@link Repository} 객체를 생성한다.
     * 생성된 {@link Repository} 객체를 사용해서 기존에 만들어져있는 Git 저장소를 참조할 수도 있고, 새 저장소를 생성할 수도 있다.
     *
     * @param ownerName
     * @param projectName
     * @return
     * @throws IOException
     */
    public static Repository buildGitRepository(String ownerName, String projectName,
                                                boolean alternatesMergeRepo) {
        try {
            RepositoryBuilder repo = new RepositoryBuilder()
                    .setGitDir(new File(getGitDirectory(ownerName, projectName)));

            if (alternatesMergeRepo) {
                repo.addAlternateObjectDirectory(new File(getDirectoryForMergingObjects(ownerName,
                        projectName)));
            }

            return repo.build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Repository buildGitRepository(String ownerName, String projectName) {
        return buildGitRepository(ownerName, projectName, true);
    }

    /**
     * {@code project}의 {@link Project#owner}와 {@link Project#name}을 사용하여 {@link Repository} 객체를 생성한다.
     *
     * when: {@link RepositoryService#gitAdvertise(models.Project, String, play.mvc.Http.Response)}와
     * {@link RepositoryService#gitRpc(models.Project, String, play.mvc.Http.Request, play.mvc.Http.Response)}에서 사용한다.
     *
     * @param project
     * @return
     * @throws IOException
     * @see #buildGitRepository(String, String, boolean)
     */
    public static Repository buildGitRepository(Project project) {
        return buildGitRepository(project, true);
    }

    public static Repository buildGitRepository(Project project, boolean alternatesMergeRepo) {
        return buildGitRepository(project.owner, project.name, alternatesMergeRepo);
    }

    /**
     * 로컬에 있는 Git 저장소를 복제(clone)한다.
     *
     * 디스크 공간을 절약하기 위해 우선 object들을 하드링크하는 클론을 시도하고,
     * 실패한 경우에는 기본 JGit 클론을 한다.
     *
     * @param originalProject
     * @param forkProject
     * @throws IOException
     * @throws GitAPIException
     */
    public static void cloneLocalRepository(Project originalProject, Project forkProject)
            throws IOException, GitAPIException {
        try {
            cloneHardLinkedRepository(originalProject, forkProject);
        } catch (Exception e) {
            new GitRepository(forkProject).delete();
            play.Logger.warn(
                    "Failed to clone a repository using hardlink. Fall back to straight copy", e);
            cloneRepository(getGitDirectoryURL(originalProject), forkProject);
        }
    }

    /**
     * bare 모드로 Git 저장소를 생성한다.
     *
     * @throws IOException
     * @see <a href="https://www.kernel.org/pub/software/scm/git/docs/gitglossary.html#def_bare_repository">bare repository</a>
     * @see Repository#create()
     */
    @Override
    public void create() throws IOException {
        this.repository.create(true);
    }

    /**
     * {@link Constants#HEAD}에서 {@code path}가 디렉토리일 경우에는 해당 디렉토리에 들어있는 파일과 디렉토리 목록을 JSON으로 반환하고,
     * 파일일 경우에는 해당 파일 정보를 JSON으로 반환한다.
     *
     * @param path
     * @return {@code path}가 디렉토리이면 그 안에 들어있는 파일과 디렉토리 목록을 담고있는 JSON, 파일이면 해당 파일 정보를 담고 있는 JSON
     * @throws IOException
     * @throws NoHeadException
     * @throws GitAPIException
     * @throws SVNException
     * @see #getMetaDataFromPath(String, String)
     */
    @Override
    public ObjectNode getMetaDataFromPath(String path) throws IOException, GitAPIException, SVNException {
        return getMetaDataFromPath(null, path);
    }

    public boolean isFile(String path, String revStr) throws IOException {
        ObjectId objectId = getObjectId(revStr);

        if (objectId == null) {
            return false;
        }

        RevWalk revWalk = new RevWalk(repository);
        RevTree revTree = revWalk.parseTree(objectId);
        TreeWalk treeWalk = new TreeWalk(repository);
        treeWalk.addTree(revTree);

        while (treeWalk.next()) {
            if (treeWalk.getPathString().equals(path) && !treeWalk.isSubtree()) {
                return true;
            }
        }

        return false;
    }

    /**
     * {@code branch}에서 {@code path}가 디렉토리일 경우에는 해당 디렉토리에 들어있는 파일과 디렉토리 목록을 JSON으로 반환하고,
     * 파일일 경우에는 해당 파일 정보를 JSON으로 반환한다.
     *
     *
     * {@code branch}가 null이면 {@link Constants#HEAD}의 Commit을 가져오고, null이 아닐때는 해당 브랜치의 head Commit을 가져온다.
     * Commit의 루트 Tree를 가져오고, {@link TreeWalk}로 해당 Tree를 순회하며 {@code path}에 해당하는 디렉토리나 파일을 찾는다.
     * {@code path}가 디렉토리이면 해당 디렉토리로 들어가서 그 안에 있는 파일과 디렉토리 목록을 JSON으로 만들어서 변환한다.
     * {@code path}가 파일이면 해당 파일 정보를 JSON으로 만들어 반환한다.
     *
     * @param branch
     * @param path
     * @return {@code path}가 디렉토리이면 그 안에 들어있는 파일과 디렉토리 목록을 담고있는 JSON, 파일이면 해당 파일 정보를 담고 있는 JSON, 존재하지 않는 path 이면 null을 반환한다.
     * @throws IOException
     * @throws GitAPIException
     */
    @Override
    public ObjectNode getMetaDataFromPath(String branch, String path) throws IOException, GitAPIException {
        RevCommit headCommit = getRevCommit(branch);
        if (headCommit == null) {
            Logger.debug("GitRepository : init Project - No Head commit");
            return null;
        }

        RevWalk revWalk = new RevWalk(repository);
        RevTree revTree = revWalk.parseTree(headCommit);
        TreeWalk treeWalk = new TreeWalk(repository);
        treeWalk.addTree(revTree);

        if (path.isEmpty()) {
            return treeAsJson(path, treeWalk, headCommit);
        }

        PathFilter pathFilter = PathFilter.create(path);
        treeWalk.setFilter(pathFilter);
        while (treeWalk.next()) {
            if (pathFilter.isDone(treeWalk)) {
                break;
            } else if (treeWalk.isSubtree()) {
                treeWalk.enterSubtree();
            }
        }

        if (treeWalk.isSubtree()) {
            treeWalk.enterSubtree();
            return treeAsJson(path, treeWalk, headCommit);
        } else {
            try {
                return fileAsJson(treeWalk, headCommit);
            } catch (MissingObjectException e) {
                Logger.debug("Unavailable access. " + branch + "/" + path + " does not exist.");
                return null;
            }
        }
    }

    /**
     * {@code treeWalk}가 현재 위치한 파일 메타데이터를 JSON 데이터로 변환하여 반환한다.
     * 그 파일에 대한 {@code untilCommitId} 혹은 그 이전 커밋 중에서 가장 최근 커밋 정보를 사용하여 Commit
     * 메시지와 author 정보등을 같이 반환한다.
     *
     * @param treeWalk
     * @param untilCommitId
     * @return
     * @throws IOException
     * @see <a href="https://www.kernel.org/pub/software/scm/git/docs/git-log.html">git log until</a>
     */
    private ObjectNode fileAsJson(TreeWalk treeWalk, AnyObjectId untilCommitId) throws IOException, GitAPIException {
        Git git = new Git(repository);

        GitCommit commit = new GitCommit(git.log()
            .add(untilCommitId)
            .addPath(treeWalk.getPathString())
            .call()
            .iterator()
            .next());

        ObjectNode result = Json.newObject();
        long commitTime = commit.getCommitTime() * 1000L;
        User author = commit.getAuthor();

        result.put("type", "file");
        result.put("msg", commit.getShortMessage());
        result.put("author", commit.getAuthorName());
        result.put("avatar", getAvatar(author));
        result.put("userName", author.name);
        result.put("userLoginId", author.loginId);
        result.put("createdDate", commitTime);
        result.put("commitMessage", commit.getShortMessage());
        result.put("commiter", commit.getCommitterName());
        result.put("commitDate", commitTime);
        result.put("commitId", untilCommitId.getName());
        ObjectLoader file = repository.open(treeWalk.getObjectId(0));
        result.put("size", file.getSize());

        boolean isBinary = RawText.isBinary(file.openStream());
        result.put("isBinary", isBinary);
        if (!isBinary && file.getSize() < MAX_FILE_SIZE_CAN_BE_VIEWED) {
            byte[] bytes = file.getBytes();
            String str = new String(bytes, FileUtil.detectCharset(bytes));
            result.put("data", str);
        }
        Metadata meta = new Metadata();
        meta.add(Metadata.RESOURCE_NAME_KEY, treeWalk.getNameString());
        result.put("mimeType", new Tika().detect(file.openStream(), meta));

        return result;
    }

    private String getAvatar(User user) {
        if(user.isAnonymous() || user.avatarUrl().equals(UserApp.DEFAULT_AVATAR_URL)) {
            return GravatarUtil.getAvatar(user.email, 34);
        } else {
            return user.avatarUrl();
        }
    }

    /**
     * Return a metadata which represents the directory in which
     * {@code treeWalk} is.
     *
     * The metadata is a set of entries represent all files and subdirectories
     * which are included in the directory. Each entry has also an information
     * about the latest commit modifying the entry.
     *
     * @param treeWalk
     * @param untilCommitId
     * @return metadata in json which represents the directory
     * @throws IOException
     * @throws GitAPIException
     * @see <a href="https://www.kernel.org/pub/software/scm/git/docs/git-log.html">git log until</a>
     */
    private ObjectNode treeAsJson(String basePath, TreeWalk treeWalk, AnyObjectId untilCommitId) throws IOException, GitAPIException {
        ObjectNode result = Json.newObject();
        ObjectNode listData = Json.newObject();
        listData.putAll(new ObjectFinder(basePath, treeWalk, untilCommitId).find());
        result.put("type", "folder");
        result.put("data", listData);
        return (listData.size() == 0) ? null : result;
    }

    public class ObjectFinder {
        private SortedMap<String, JsonNode> found = new TreeMap<>();
        private Map<String, JsonNode> targets = new HashMap<>();
        private String basePath;
        private Iterator<RevCommit> commitIterator;

        public ObjectFinder(String basePath, TreeWalk treeWalk, AnyObjectId untilCommitId) throws IOException, GitAPIException {
            while (treeWalk.next()) {
                String path = treeWalk.getNameString();
                ObjectNode object = Json.newObject();
                object.put("type", treeWalk.isSubtree() ? "folder" : "file");
                targets.put(path, object);
            }
            this.basePath = basePath;
            this.commitIterator = getCommitIterator(untilCommitId);
        }

        public SortedMap<String, JsonNode> find() throws IOException {
            RevCommit prev = null;
            RevCommit curr = null;
            int i = 0;

            // Empty targets means we have found every interested objects and
            // no need to continue.
            for (; i < COMMIT_HISTORY_LIMIT; i++) {
                if (targets.isEmpty()) {
                    break;
                }

                if (!commitIterator.hasNext()) {
                    // ** Illegal state detected! (JGit bug?) **
                    //
                    // If targets still remain but there is no next commit,
                    // something is wrong because the directory contains the
                    // targets does not have any commit modified them. Sometimes
                    // it occurs and it seems a bug of JGit. For the bug report,
                    // see http://dev.eclipse.org/mhonarc/lists/jgit-dev/msg02461.html
                    try {
                        commitIterator = getCommitIterator(curr.getId());
                    } catch (GitAPIException e) {
                        play.Logger.warn("An exception occurs while traversing git history", e);
                        break;
                    }
                }

                curr = commitIterator.next();

                if (curr.equals(prev)) {
                    break;
                }

                found(curr, findObjects(curr));

                prev = curr;
            }

            if (i >= COMMIT_HISTORY_LIMIT) {
                play.Logger.warn("Stopped object traversal of '" + basePath + "' in '" +
                        repository + "' because of reaching the limit");
            }

            return found;
        }

        /*
         * get commit logs with untilCommitId and basePath
         */
        private Iterator<RevCommit> getCommitIterator(AnyObjectId untilCommitId) throws
                IOException, GitAPIException {
            Git git = new Git(repository);
            LogCommand logCommand = git.log().add(untilCommitId);
            if (StringUtils.isNotEmpty(basePath)) {
                logCommand.addPath(basePath);
             }
            final Iterator<RevCommit> iterator = logCommand.call().iterator();
            return new Iterator<RevCommit>() {
                @Override
                public void remove() {
                    iterator.remove();
                }

                @Override
                public RevCommit next() {
                    return fixRevCommitNoParents(iterator.next());
                }

                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }
            };
        }

        private Map<String, ObjectId> findObjects(RevCommit commit) throws IOException {
            final Map<String, ObjectId> objects = new HashMap<>();

            // We want to find the latest commit for each of `targets`. We already know they have
            // same `basePath`. So get every blobs and trees match one of `targets`, under the
            // `basePath`, and put them into `objects`.
            TreeWalkHandler objectCollector = new TreeWalkHandler() {
                @Override
                public void handle(TreeWalk treeWalk) {
                    if (targets.containsKey(treeWalk.getNameString())) {
                        objects.put(treeWalk.getNameString(), treeWalk.getObjectId(0));
                    }
                }
            };

            // Remove every blob and tree from `objects` if any of parent commits have a
            // object whose path and id is identical with the blob or the tree. It means the
            // blob or tree is not changed so we are not interested in it.
            TreeWalkHandler objectRemover = new TreeWalkHandler() {
                @Override
                public void handle(TreeWalk treeWalk) {
                    if (treeWalk.getObjectId(0).equals(objects.get(treeWalk.getNameString()))) {
                        objects.remove(treeWalk.getNameString());
                    }
                }
            };

            // Choose only objects which interest from the blobs and trees. We are interested in
            // blobs and trees which has change between the last commit and the current commit.
            traverseTree(commit, objectCollector);
            for(RevCommit parent : commit.getParents()) {
                RevCommit fixedParent = fixRevCommitNoTree(parent);
                traverseTree(fixedParent, objectRemover);
            }
            return objects;
        }

        private void traverseTree(RevCommit commit, TreeWalkHandler handler) throws IOException {
            TreeWalk treeWalk;
            if (StringUtils.isEmpty(basePath)) {
                treeWalk = new TreeWalk(repository);
                treeWalk.addTree(commit.getTree());
            } else {
                treeWalk = TreeWalk.forPath(repository, basePath, commit.getTree());
                if (treeWalk == null) {
                    return;
                }
                treeWalk.enterSubtree();
            }
            while (treeWalk.next()) {
                handler.handle(treeWalk);
            }
        }

        /*
         * JGit 의 LogCommand 를 사용하여 commit 조회를 할 때 path 정보를 이용하였을 경우
         * commit 객체가 부모 commit 에 대한 정보를 가지고 있지 않을 수 있다.
         * 이러한 현상은 아래의 JGit version 에서 확인 되었다.
         * 3.1.0.201310021548-r ~ 3.2.0.201312181205-r
         */
        private RevCommit fixRevCommitNoParents(RevCommit commit) {
            if (commit.getParentCount() == 0) {
                return fixRevCommit(commit);
            }
            return commit;
        }

        /*
         * fixRevCommitNoParents 를 통해서 가져온 커밋의 parents 는 tree 정보가 없을 수 있다
         */
        private RevCommit fixRevCommitNoTree(RevCommit commit) {
            if (commit.getTree() == null) {
                return fixRevCommit(commit);
            }
            return commit;
        }

        private RevCommit fixRevCommit(RevCommit commit) {
            RevWalk revWalk = new RevWalk(repository);
            try {
                return revWalk.parseCommit(commit);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        /*
         * Now, all `objects` interest. Get metadata from the objects, put
         * them into `found` and remove from `targets`.
         */
        private void found(RevCommit revCommit, Map<String, ObjectId> objects) {
            for (String path : objects.keySet()) {
                GitCommit commit = new GitCommit(revCommit);
                ObjectNode data = (ObjectNode) targets.get(path);
                data.put("msg", commit.getShortMessage());
                String emailAddress = commit.getAuthorEmail();
                User user = User.findByEmail(emailAddress);
                data.put("avatar", getAvatar(user));
                data.put("userName", user.name);
                data.put("userLoginId", user.loginId);
                data.put("createdDate", revCommit.getCommitTime() * 1000l);
                data.put("author", commit.getAuthorName());
                data.put("commitId", commit.getShortId());
                data.put("commitUrl", routes.CodeHistoryApp.show(ownerName, projectName, commit.getShortId()).url());
                found.put(path, data);
                targets.remove(path);
            }
        }
    }

    public static interface TreeWalkHandler {
        void handle(TreeWalk treeWalk);
    }

    /**
     * Returns the contents of the file matched up with the given revision and
     * path.
     *
     * @param revision
     * @param path
     * @return null if the {@code path} denotes a directory, or the contents if
     *         not
     * @throws IOException
     */
    @Override
    public byte[] getRawFile(String revision, String path) throws IOException {
        RevTree tree = new RevWalk(repository).parseTree(repository.resolve(revision));
        TreeWalk treeWalk = TreeWalk.forPath(repository, path, tree);
        if (treeWalk.isSubtree()) {
            return null;
        } else {
            return repository.open(treeWalk.getObjectId(0)).getBytes();
        }
    }

    /**
     * Deletes the directory of this Git repository.
     *
     * By {@code repository.close()}, return open resources of repository and
     * remove the references to packFile by initializing {@code Cache} used in
     * the repository.
     */
    @Override
    public void delete() {
        repository.close();
        WindowCacheConfig config = new WindowCacheConfig();
        config.install();
        FileUtil.rm_rf(repository.getDirectory());
    }

    /**
     * Returns the patch made by the commit denoted by the given revision.
     *
     * The patch comes from the difference between the root tree of the commit
     * denoted by the given revision and the root tree of the parent commit. If
     * there is no parent commit, compare with an empty tree.
     *
     * @param rev
     * @return the string form of the patch in unified diff format
     * @throws GitAPIException
     * @throws IOException
     */
    @Override
    public String getPatch(String rev) throws IOException {
        RevCommit commit = getRevCommit(rev);

        if (commit == null) {
            return null;
        }

        RevCommit parent = null;
        if (commit.getParentCount() > 0) {
            parent = parseCommit(commit.getParent(0));
        }

        return getPatch(parent, commit);
    }

    @Override
    public String getPatch(String revA, String revB) throws IOException {
        RevCommit commitA = getRevCommit(revA);
        RevCommit commitB = getRevCommit(revB);

        if (commitA == null || commitB == null) {
            return null;
        }

        return getPatch(commitA, commitB);
    }

    /*
     * Render the difference from treeWalk which has two trees.
     */
    private String getPatch(RevCommit commitA, RevCommit commitB) throws IOException {
        TreeWalk treeWalk = new TreeWalk(repository);
        addTree(treeWalk, commitA);
        addTree(treeWalk, commitB);
        treeWalk.setRecursive(true);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DiffFormatter diffFormatter = new DiffFormatter(out);
        diffFormatter.setRepository(repository);
        diffFormatter.format(DiffEntry.scan(treeWalk));

        return out.toString("UTF-8");
    }

    private void addTree(TreeWalk treeWalk, RevCommit commit) throws IOException {
        if (commit == null) {
            treeWalk.addTree(new EmptyTreeIterator());
        } else {
            treeWalk.addTree(commit.getTree());
        }
    }

    /**
     * Returns the difference made by the commit denoted by the given revision.
     *
     * The patch comes from the difference between the root tree of the commit
     * denoted by the given revision and the root tree of the parent commit. If
     * there is no parent commit, compare with an empty tree.
     *
     * @param rev the revision
     * @return the list of differences of files
     * @throws GitAPIException
     * @throws IOException
     */
    public List<FileDiff> getDiff(String rev) throws IOException {
        return getDiff(repository, rev);
    }

    static public List<FileDiff> getDiff(Repository repository, String rev) throws IOException {
        return getDiff(repository, repository.resolve(rev));
    }

    public List<FileDiff> getDiff(ObjectId commitId) throws IOException {
        return getDiff(repository, commitId);
    }

    static public List<FileDiff> getDiff(Repository repository, ObjectId commitId) throws
            IOException {
        if (commitId == null) {
            return null;
        }

        // Get the trees, from current commit and its parent, as treeWalk.
        RevWalk revWalk = new RevWalk(repository);
        RevCommit commit = revWalk.parseCommit(commitId);
        ObjectId commitIdA = null;
        if (commit.getParentCount() > 0) {
            commitIdA = commit.getParent(0).getId();
        }

        return getFileDiffs(repository, repository, commitIdA, commitId);
    }

    public List<FileDiff> getDiff(RevCommit commit) throws IOException {
        ObjectId commitIdA = null;
        if (commit.getParentCount() > 0) {
            commitIdA = commit.getParent(0).getId();
        }

        return getFileDiffs(repository, repository, commitIdA, commit.getId());
    }

    public static List<FileDiff> getDiff(Repository repository, RevCommit commit) throws IOException {
        ObjectId commitIdA = null;
        if (commit.getParentCount() > 0) {
            commitIdA = commit.getParent(0).getId();
        }

        return getFileDiffs(repository, repository, commitIdA, commit.getId());
    }


    /**
     * Returns commits until the given revision.
     *
     * @param pageNumber a 0-based page number
     * @param pageSize
     * @param untilRevName a revision; If null, it returns commits until HEAD.
     * @return list of the commits
     * @throws IOException
     * @throws GitAPIException
     */
    @Override
    public List<Commit> getHistory(int pageNumber, int pageSize, String untilRevName, String path)
            throws IOException, GitAPIException {
        // Get the list of commits from HEAD to the given pageNumber.
        LogCommand logCommand = new Git(repository).log();
        if (path != null) {
            logCommand.addPath(path);
        }

        RevCommit start = getRevCommit(untilRevName);
        if (start == null) {
            return null;
        }
        logCommand.add(start);

        Iterable<RevCommit> iter = logCommand.setMaxCount(pageNumber * pageSize + pageSize).call();
        List<RevCommit> list = new LinkedList<>();
        for (RevCommit commit : iter) {
            if (list.size() >= pageSize) {
                list.remove(0);
            }
            list.add(commit);
        }

        List<Commit> result = new ArrayList<>();
        for (RevCommit commit : list) {
            result.add(new GitCommit(commit));
        }

        return result;
    }

    @Override
    public Commit getCommit(String rev) throws IOException {
        ObjectId commitId = repository.resolve(rev);

        if (commitId == null) {
            return null;
        }

        return new GitCommit(new RevWalk(repository).parseCommit(commitId));
    }

    /**
     * Returns names of all branches.
     *
     * @return list of the name strings
     */
    @Override
    public List<String> getBranches() {
        return new ArrayList<>(repository.getAllRefs().keySet());
    }

    public List<GitBranch> getAllBranches() throws IOException, GitAPIException {
        List<GitBranch> branches = new ArrayList<>();

        for(Ref branchRef : new Git(repository).branchList().call()) {
            RevWalk walk = new RevWalk(repository);
            RevCommit head = walk.parseCommit(branchRef.getObjectId());
            walk.dispose();

            GitBranch newBranch = new GitBranch(branchRef.getName(), new GitCommit(head));
            setTheLatestPullRequest(newBranch);

            branches.add(newBranch);
        }

        Collections.sort(branches, new Comparator<GitBranch>() {
            @Override
            public int compare(GitBranch b1, GitBranch b2) {
                return b2.getHeadCommit().getCommitterDate().compareTo(b1.getHeadCommit().getCommitterDate());
            }
        });

        return branches;
    }

    private void setTheLatestPullRequest(GitBranch gitBranch) {
        Project project = Project.findByOwnerAndProjectName(ownerName, projectName);
        gitBranch.setPullRequest(PullRequest.findTheLatestOneFrom(project, gitBranch.getName()));
    }

    @Override
    public Resource asResource() {
        return new Resource() {
            @Override
            public String getId() {
                return null;
            }

            @Override
            public Project getProject() {
                return ProjectApp.getProject(ownerName, projectName);
            }

            @Override
            public ResourceType getType() {
                return ResourceType.CODE;
            }

        };
    }

    @Override
    public boolean isFile(String path) throws IOException {
        return isFile(path, Constants.HEAD);
    }

    /**
     * Returns the directory path to the Git repository.
     *
     * This method is used when create {@link Repository} object which refers
     * a Git repository.
     *
     * @param project
     * @return
     * @see #getGitDirectory(String, String)
     */
    public static String getGitDirectory(Project project) {
        return getGitDirectory(project.owner, project.name);
    }

    /**
     * Returns the url to the Git repository.
     *
     * This method is used when a URL to Git repository is required for clone,
     * fetch and push command.
     *
     * @param project
     * @return
     * @throws IOException
     */
    public static String getGitDirectoryURL(Project project) throws IOException {
        String currentDirectory = new java.io.File( "." ).getCanonicalPath();
        return currentDirectory + "/" + getGitDirectory(project);
    }

    /**
     * Returns the directory path to the Git repository.
     *
     * This method is used when create {@link Repository} object which refers
     * a Git repository.
     *
     * @param ownerName
     * @param projectName
     * @return
     */
    public static String getGitDirectory(String ownerName, String projectName) {
        return getRepoPrefix() + ownerName + "/" + projectName + ".git";
    }

    /**
     * Clone a Git repository.
     *
     * Create a bare repository and copy all branches from the origin repository
     * into it.
     *
     * @param gitUrl the url to the origin repository
     * @param forkingProject the project to have the cloned repository
     * @throws GitAPIException
     * @throws IOException
     * * @see <a href="https://www.kernel.org/pub/software/scm/git/docs/gitglossary.html#def_bare_repository">bare repository</a>
     */
    public static void cloneRepository(String gitUrl, Project forkingProject) throws GitAPIException {
        String directory = getGitDirectory(forkingProject);
        Git.cloneRepository()
                .setURI(gitUrl)
                .setDirectory(new File(directory))
                .setCloneAllBranches(true)
                .setBare(true)
                .call();
    }

    public static void deleteMergingDirectory(PullRequest pullRequest) {
        Project toProject = pullRequest.toProject;
        String directoryForMerging = GitRepository.getDirectoryForMerging(toProject.owner, toProject.name);
        FileUtil.rm_rf(new File(directoryForMerging));
    }

    /**
     * Push branches.
     *
     * @param repository the source repository
     * @param remote the destination repository
     * @param src src of refspec
     * @param dest dst of refspec
     * @throws GitAPIException
     */
    public static void push(Repository repository, String remote, String src, String dest) throws GitAPIException {
        new Git(repository).push()
                .setRemote(remote)
                .setRefSpecs(new RefSpec(src + ":" + dest))
                .call();
    }

    /**
     * Merge a branch.
     *
     * Create a merge commit even when the merge resolves as a fast-forward, by
     * adding --no-ff option.
     *
     * @param repository
     * @param branchName the name of the branch to be merged.
     * @return
     * @throws GitAPIException
     * @throws IOException
     */
    public static MergeResult merge(Repository repository, String branchName) throws GitAPIException, IOException {
        ObjectId branch = repository.resolve(branchName);
        return new Git(repository).merge()
                .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                .include(branch)
                .call();
    }

    /**
     * Checkout the branch.
     *
     * @param repository
     * @param branchName the name of the branch to checkout
     * @throws GitAPIException
     */
    public static void checkout(Repository repository, String branchName) throws GitAPIException {
        new Git(repository).checkout()
                .setName(branchName)
                .setCreateBranch(false)
                .call();
    }

    /**
     * Returns the path to working tree.
     *
     * This method is used for merge.
     *
     * @param owner
     * @param projectName
     * @return
     */
    public static String getDirectoryForMerging(String owner, String projectName) {
        return getRepoForMergingPrefix() + owner + "/" + projectName + ".git";
    }

    public static String getDirectoryForMergingObjects(String owner, String projectName) {
        return getDirectoryForMerging(owner, projectName) + "/.git/objects";
    }

    @SuppressWarnings("unchecked")
    public static List<RevCommit> diffRevCommits(Repository repository, String fromBranch, String toBranch) throws IOException {
        RevWalk walk = null;
        try {
            walk = new RevWalk(repository);
            ObjectId from = repository.resolve(fromBranch);
            ObjectId to = repository.resolve(toBranch);

            walk.markStart(walk.parseCommit(from));
            walk.markUninteresting(walk.parseCommit(to));

            return IteratorUtils.toList(walk.iterator());
        } finally {
            if (walk != null) {
                walk.dispose();
            }
        }
    }

    public static List<GitCommit> diffCommits(Repository repository, String fromBranch, String toBranch) throws IOException {
        List<GitCommit> commits = new ArrayList<>();
        List<RevCommit> revCommits = diffRevCommits(repository, fromBranch, toBranch);
        for (RevCommit revCommit : revCommits) {
            commits.add(new GitCommit(revCommit));
        }
        return commits;
    }

    /**
     * Find authors of changes between the given revisions.
     *
     * Find the authors by the email addresses of the person who modified the
     * changed line most recently.
     *
     * @param repository
     * @param revA
     * @param revB
     * @return
     * @throws IOException
     * @throws GitAPIException
     */
    public static Set<User> getRelatedAuthors(Repository repository, String revA, String revB)
            throws IOException, GitAPIException {
        Set<User> authors = new HashSet<>();
        RevWalk revWalk = null;

        try {
            revWalk = new RevWalk(repository);
            RevCommit commitA = revWalk.parseCommit(repository.resolve(revA));
            RevCommit commitB = revWalk.parseCommit(repository.resolve(revB));
            List<DiffEntry> diffs = getDiffEntries(repository, commitA, commitB);

            for (DiffEntry diff : diffs) {
                if (isTypeMatching(diff.getChangeType(), MODIFY, DELETE)) {
                    authors.addAll(getAuthorsFromDiffEntry(repository, diff, commitA));
                }
                if (isTypeMatching(diff.getChangeType(), RENAME)) {
                    authors.add(getAuthorFromFirstCommit(repository, diff.getOldPath(), commitA));
                }
            }
        } finally {
            if (revWalk != null) {
                revWalk.dispose();
            }
        }

        authors.remove(User.anonymous);
        return authors;
    }

    /**
     * Returns the difference between the given commits.
     *
     * @param repository
     * @param commitA
     * @param commitB
     * @return list of {@link org.eclipse.jgit.diff.DiffEntry}
     * @throws IOException
     */
    private static List<DiffEntry> getDiffEntries(Repository repository, RevCommit commitA,
            RevCommit commitB) throws IOException {
        DiffFormatter diffFormatter = new DiffFormatter(NullOutputStream.INSTANCE);
        try {
            diffFormatter.setRepository(repository);
            diffFormatter.setDetectRenames(true);
            return diffFormatter.scan(commitA, commitB);
        } finally {
            diffFormatter.release();
        }
    }

    /**
     * Check if {@code types} contain the given {@code type}
     *
     * @param type
     * @param types
     * @return
     */
    private static boolean isTypeMatching(Object type, Object... types) {
        return ArrayUtils.contains(types, type);
    }

    /**
     * Find authors of modified or removed lines from the given edit list.
     *
     * @param repository
     * @param diff the edit list
     * @param start the oldest commit to be considered
     * @return
     * @throws GitAPIException
     * @throws IOException
     */
    private static Set<User> getAuthorsFromDiffEntry(Repository repository, DiffEntry diff,
            RevCommit start) throws GitAPIException, IOException {
        DiffFormatter diffFormatter = new DiffFormatter(NullOutputStream.INSTANCE);
        try {
            diffFormatter.setRepository(repository);
            EditList edits = diffFormatter.toFileHeader(diff).toEditList();
            BlameResult blameResult = new Git(repository).blame()
                    .setFilePath(diff.getOldPath())
                    .setFollowFileRenames(true)
                    .setStartCommit(start).call();
            return getAuthorsFromBlameResult(edits, blameResult);
        } finally {
            diffFormatter.release();
        }
    }

    /**
     * Find authors of modified or removed lines from the given edit list and
     * the result of blame.
     *
     * @param edits an edit list
     * @param blameResult the result of blame
     * @return set of the authors
     */
    private static Set<User> getAuthorsFromBlameResult(EditList edits, BlameResult blameResult) {
        Set<User> authors = new HashSet<>();
        for (Edit edit : edits) {
            if (isTypeMatching(edit.getType(), Type.REPLACE, Type.DELETE)) {
                for (int i = edit.getBeginA(); i < edit.getEndA(); i++) {
                    authors.add(findAuthorByPersonIdent(blameResult.getSourceAuthor(i)));
                }
            }
        }
        return authors;
    }

    /**
     * Find the author of the file denoted by the given path.
     *
     * @param repository
     * @param path
     * @param start Consider only the commits since {@code start}.
     * @return
     * @throws IOException
     */
    private static User getAuthorFromFirstCommit(Repository repository, String path, RevCommit start)
            throws IOException {
        RevWalk revWalk = null;
        try {
            revWalk = new RevWalk(repository);
            revWalk.markStart(start);
            revWalk.setTreeFilter(PathFilter.create(path));
            revWalk.sort(RevSort.REVERSE);
            RevCommit commit = revWalk.next();
            // 어떤 파일이 처음 생성된 commit 은 반드시 존재해야 한다.
            // 하지만 어떤 이유에선지 위와 같이 RevWalk 를 이용했을 때 그 commit 을 찾지 못할 때가 있다.
            // 아래 commit 이 null 일 경우의 처리는 임시적인 것이며 추후 원인을 분석해서 특정 path 의
            // 파일이 생성된 commit 을 항상 찾도록 고쳐야 한다.
            if (commit == null) {
                return User.anonymous;
            }
            return findAuthorByPersonIdent(commit.getAuthorIdent());
        } finally {
            if (revWalk != null) {
                revWalk.dispose();
            }
        }
    }

    /**
     * Find the user who matchs up with the {@code personIdent}.
     *
     * @param personIdent
     * @return
     */
    private static User findAuthorByPersonIdent(PersonIdent personIdent) {
        if (personIdent == null) {
            return User.anonymous;
        }
        return User.findByEmail(personIdent.getEmailAddress());
    }

    /**
     * Delete the branch denoted by the given name.
     *
     * @param repository
     * @param branchName
     * @throws GitAPIException
     */
    public static void deleteBranch(Repository repository, String branchName) throws GitAPIException {
        new Git(repository).branchDelete()
                .setBranchNames(branchName)
                .setForce(true)
                .call();
    }

    /**
     * Fetch branches.
     *
     * @param repository Stores the fetched objects and refs in this repository.
     * @param project Fetches from this project.
     * @param fromBranch src of refspec
     * @param toBranch dst of refspec
     * @throws GitAPIException
     * @throws IOException
     * @see <a href="https://www.kernel.org/pub/software/scm/git/docs/git-fetch.html">git-fetch</a>
     */
    public static void fetch(Repository repository, Project project, String fromBranch, String toBranch) throws GitAPIException, IOException {
        new Git(repository).fetch()
                .setRemote(GitRepository.getGitDirectoryURL(project))
                .setRefSpecs(new RefSpec(fromBranch + ":" + toBranch))
                .call();
    }

    /**
     * 풀리퀘스트 기능 구현에 필요한 기본 작업을 수행하는 템플릿 메서드
     *
     * when: {@link models.PullRequest#merge}, {@link models.PullRequest#attemptMerge()}에서 사용한다.
     *
     * 1. merge용 저장소를 성성한다.
     *   {@code pullRequest}의 toProject에 해당하는 저장소를 clone.
     * 2. 코드 보내는 브랜치를 가져온다.
     *   {@code pullRequest}의 fromProject 저장소에서 fromBranch fetch.
     * 3. 코드 받을 브랜치를 가져온다.
     *   {@code pullRequest}의 toProject 저장소에서 toBranch fetch.
     * 4. 코드 받을 브랜치에서 merge할 때 사용할 새로운 브랜치를 생성한다.
     *   git checkout -b 현재시간 destToBranchName
     *
     * @param pullRequest
     * @param operation
     * @see models.PullRequest#attemptMerge()
     * @see models.PullRequest#merge(models.PullRequestEventMessage)
     */
    public static void cloneAndFetch(PullRequest pullRequest, AfterCloneAndFetchOperation operation) {
        Repository cloneRepository = null;
        String mergingBranch = null;
        String destFromBranchName = null;
        try {
            synchronized (PROJECT_LOCK.get(pullRequest.toProject)) {
                cloneRepository = buildMergingRepository(pullRequest);

                String srcToBranchName = pullRequest.toBranch;
                String destToBranchName = makeDestToBranchName(pullRequest);
                String srcFromBranchName = pullRequest.fromBranch;
                destFromBranchName = makeDestFromBranchName(pullRequest);
                mergingBranch = "" + System.currentTimeMillis();

                // 코드를 보내는 브랜치를 가져온다.
                new Git(cloneRepository).fetch()
                        .setRemote(GitRepository.getGitDirectoryURL(pullRequest.fromProject))
                        .setRefSpecs(new RefSpec("+" + srcFromBranchName + ":" + destFromBranchName))
                        .call();

                // 코드 받을 브랜치를 가져온다.
                new Git(cloneRepository).fetch()
                        .setRemote(GitRepository.getGitDirectoryURL(pullRequest.toProject))
                        .setRefSpecs(new RefSpec("+" + srcToBranchName + ":" + destToBranchName))
                        .call();

                // 현재 위치 정리.
                new Git(cloneRepository).reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call();
                new Git(cloneRepository).clean().setIgnore(true).setCleanDirectories(true).call();

                // mergingBranch 생성 및 이동
                new Git(cloneRepository).checkout()
                        .setCreateBranch(true)
                        .setName(mergingBranch)
                        .setStartPoint(destToBranchName)
                        .call();

                // Operation 실행. (현재 위치는 mergingBranch)
                CloneAndFetch cloneAndFetch = new CloneAndFetch(cloneRepository, destToBranchName, destFromBranchName, mergingBranch);
                operation.invoke(cloneAndFetch);

                // 코드 받을 브랜치로 이동하고 mergingBranch 삭제
                new Git(cloneRepository).checkout().setName(destToBranchName).call();
                new Git(cloneRepository).branchDelete().setForce(true).setBranchNames(mergingBranch).call();
            }
        } catch (GitAPIException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            if(cloneRepository != null) {
                try {
                    if(destFromBranchName != null) {
                        // 코드 보내는 브랜치로 이동
                        new Git(cloneRepository).checkout().setName(destFromBranchName).call();
                    }
                    if(mergingBranch != null) {
                        // merge 브랜치 삭제
                        new Git(cloneRepository).branchDelete().setForce(true).setBranchNames(mergingBranch).call();
                    }
                } catch (GitAPIException e) {
                    Logger.error("failed to delete merging branch", e);
                }

                cloneRepository.close();
            }
        }
    }

    private static String makeDestToBranchName(PullRequest pullRequest) {
        return Constants.R_REMOTES +
            pullRequest.toProject.owner + "/" +
            pullRequest.toProject.name + "/" +
            pullRequest.toBranch.replaceFirst(Constants.R_HEADS, "");
    }

    private static String makeDestFromBranchName(PullRequest pullRequest) {
        return Constants.R_REMOTES +
            pullRequest.fromProject.owner + "/" +
            pullRequest.fromProject.name + "/" +
            pullRequest.fromBranch.replaceFirst(Constants.R_HEADS, "");
    }

    /**
     * {@code pullRequest}의 toProject를 clone 받은 Git 저장소를 참조할 {@link Repository}를 객체를 생성한다.
     *
     * @param pullRequest
     * @return
     * @throws GitAPIException
     * @throws IOException
     */
    public static Repository buildMergingRepository(PullRequest pullRequest) {
        return buildMergingRepository(pullRequest.toProject);
    }

    public static Repository buildMergingRepository(Project project) {
        // merge 할 때 사용할 Git 저장소 디렉토리 경로를 생성한다.
        String workingTree = GitRepository.getDirectoryForMerging(project.owner, project.name);

        try {
            // 이미 만들어둔 clone 디렉토리가 있다면 그걸 사용해서 Repository를 생성하고
            // 없을 때는 새로 만든다.
            File gitDir = new File(workingTree + "/.git");
            if(!gitDir.exists()) {
                return cloneRepository(project, workingTree).getRepository();
            } else {
                return new RepositoryBuilder().setGitDir(gitDir).build();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * {@link Project}의 Git 저장소를 {@code workingTreePath}에
     * non-bare 모드로 clone 한다.
     *
     * @param project clone 받을 프로젝트
     * @param workingTreePath clone 프로젝트를 생성할 워킹트리 경로
     * @throws GitAPIException
     * @throws IOException
     */
    private static Git cloneRepository(Project project, String workingTreePath) throws GitAPIException, IOException {
        return Git.cloneRepository()
                .setURI(GitRepository.getGitDirectoryURL(project))
                .setDirectory(new File(workingTreePath))
                .call();
    }

    /**
     * {@code pullRequest}의 fromBranch를 삭제할 수 있는지 확인한다.
     *
     * 삭제하려는 fromBranch가 브랜치 목록에 있는지 확인하고,
     * 브랜치 목록에 있을 때 fromBranch의 HEAD가 toProject에 있는지 확인한다.
     *
     * 브랜치 목록에 있으면서 toProject에 fromBranch의 HEAD가 있을 경우에만 fromBranch를 안전하게 삭제할 수 있다.
     *
     * 브랜치 목록에 없을 때는 이미 삭제 됐거나 현재 위치한 브랜치(master)에 있을 수 있어서 fromBranch를 삭제할 수 없거나,
     * fromBranch에 toProject로 보내지 않은 새로운 커밋이 있어서 fromBranch를 삭제할 수 없다.
     *
     * @param pullRequest
     * @return
     */
    public static boolean canDeleteFromBranch(PullRequest pullRequest) {
        List<Ref> refs;
        Repository fromRepo = null; // repository that sent the pull request
        String currentBranch;
        try {
            fromRepo = buildGitRepository(pullRequest.fromProject);
            currentBranch = fromRepo.getFullBranch();
            refs = new Git(fromRepo).branchList().call();

            for(Ref branchRef : refs) {
                String branchName = branchRef.getName();
                if(branchName.equals(pullRequest.fromBranch) && !branchName.equals(currentBranch)) {
                    RevWalk revWalk = new RevWalk(fromRepo);
                    RevCommit commit = revWalk.parseCommit(fromRepo.resolve(branchName));
                    String commitName = commit.name(); // fromBranch's head commit name
                    revWalk.release();

                    // check whether the target repository has the commit witch is the fromBranch's head commit.
                    Repository toRepo = buildGitRepository(pullRequest.toProject);
                    ObjectId toBranch = toRepo.resolve(commitName);
                    if(toBranch != null) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if(fromRepo != null) {
                fromRepo.close();
            }
        }

        return false;
    }

    /**
     * {@code pullRequest}의 fromBranch를 삭제한다.
     *
     * @param pullRequest
     * @return {@code fromBranch}의 HEAD를 반환한다.
     * @see PullRequest#lastCommitId;
     */
    public static String deleteFromBranch(PullRequest pullRequest) {
        if(!canDeleteFromBranch(pullRequest)) {
            return null;
        }

        RevWalk revWalk = null;
        String lastCommitId;
        Repository repo = null;
        try {
            repo = buildGitRepository(pullRequest.fromProject);
            ObjectId branch = repo.resolve(pullRequest.fromBranch);
            revWalk = new RevWalk(repo);
            RevCommit commit = revWalk.parseCommit(branch);
            lastCommitId = commit.getName();
            deleteBranch(repo, pullRequest.fromBranch);
            return lastCommitId;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if(revWalk != null) {
                revWalk.release();
            }
            if(repo != null) {
                repo.close();
            }
        }
    }

    /**
     * {@code pullRequest}의 fromBranch를 복구한다.
     *
     * @param pullRequest
     */
    public static void restoreBranch(PullRequest pullRequest) {
        if(!canRestoreBranch(pullRequest)) {
            return;
        }

        Repository repo = null;
        try {
            repo = buildGitRepository(pullRequest.fromProject);
            new Git(repo).branchCreate()
                    .setName(pullRequest.fromBranch.replaceAll("refs/heads/", ""))
                    .setStartPoint(pullRequest.lastCommitId)
                    .call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if(repo != null) {
                repo.close();
            }
        }
    }

    /**
     * {@code pullRequest}의 fromBranch를 복구할 수 있는지 확인한다.
     *
     * when: 완료된 PullRequest 조회 화면에서 브랜치를 삭제 했을 때 해당 브랜치를 복구할 수 있는지 확인한다.
     *
     * {@link PullRequest#lastCommitId}가 저장되어 있어야 하며, fromBranch가 없어야 복구할 수 있다.
     *
     * @param pullRequest
     * @return
     */
    public static boolean canRestoreBranch(PullRequest pullRequest) {
        Repository repo = null;
        try {
            repo = buildGitRepository(pullRequest.fromProject);
            ObjectId resolve = repo.resolve(pullRequest.fromBranch);
            if(resolve == null && pullRequest.lastCommitId != null) {
                return true;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if(repo != null) {
                repo.close();
            }
        }

        return false;
    }

    public static List<GitCommit> diffCommits(PullRequest pullRequest) {
        List<GitCommit> commits = new ArrayList<>();
        if(pullRequest.mergedCommitIdFrom == null || pullRequest.mergedCommitIdTo == null) {
            return commits;
        }

        Repository repo;
        try {
            if(pullRequest.isClosed()) {
                repo = buildGitRepository(pullRequest.toProject);
            } else {
                repo = buildGitRepository(pullRequest.fromProject);
            }

            ObjectId untilId = repo.resolve(pullRequest.mergedCommitIdTo);
            if(untilId == null) {
                return commits;
            }
            ObjectId sinceId = repo.resolve(pullRequest.mergedCommitIdFrom);
            if(sinceId == null) {
                return commits;
            }

            Iterable<RevCommit> logIterator = new Git(repo).log().addRange(sinceId, untilId).call();
            for(RevCommit commit : logIterator) {
                commits.add(new GitCommit(commit));
            }

            return commits;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String getPatch(Repository repository, String fromBranch, String toBranch) {
        TreeWalk treeWalk = new TreeWalk(repository);
        RevWalk walk = new RevWalk(repository);
        try {
            ObjectId from = repository.resolve(fromBranch);
            ObjectId to = repository.resolve(toBranch);
            RevTree fromTree = walk.parseTree(from);
            RevTree toTree = walk.parseTree(to);

            treeWalk.addTree(toTree);
            treeWalk.addTree(fromTree);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DiffFormatter diffFormatter = new DiffFormatter(out);
            diffFormatter.setRepository(repository);
            treeWalk.setRecursive(true);
            diffFormatter.format(DiffEntry.scan(treeWalk));

            return out.toString("UTF-8");
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            walk.dispose();
        }
    }

    public static String getPatch(PullRequest pullRequest) {
        if(pullRequest.mergedCommitIdFrom == null || pullRequest.mergedCommitIdTo == null) {
            return "";
        }

        Repository repo;
        try {
            repo = buildGitRepository(pullRequest.toProject);

            ObjectId untilId = repo.resolve(pullRequest.mergedCommitIdTo);
            if(untilId == null) {
                return "";
            }
            ObjectId sinceId = repo.resolve(pullRequest.mergedCommitIdFrom);
            if(sinceId == null) {
                return "";
            }

            return getPatch(repo, untilId.getName(), sinceId.getName());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static List<FileDiff> getDiff(final Repository repository, String revA, String revB) throws IOException {
        return getDiff(repository, revA, repository, revB);
    }

    @Override
    public List<FileDiff> getDiff(String revA, String revB) throws IOException {
        return getDiff(this.repository, revA, revB);
    }

    public static List<FileDiff> getDiff(final Repository repositoryA, String revA, Repository repositoryB, String revB) throws IOException {
        ObjectId commitA = repositoryA.resolve(revA);
        ObjectId commitB = repositoryB.resolve(revB);

        return getFileDiffs(repositoryA, repositoryB, commitA, commitB);
    }

    private static List<FileDiff> getFileDiffs(final Repository repositoryA, Repository repositoryB, ObjectId commitA, ObjectId commitB) throws IOException {
        class MultipleRepositoryObjectReader extends ObjectReader {
            Collection<ObjectReader> readers = new HashSet<>();

            @Override
            public ObjectReader newReader() {
                return new MultipleRepositoryObjectReader(readers);
            }

            public MultipleRepositoryObjectReader(Collection<ObjectReader> readers) {
                this.readers = readers;
            }

            public MultipleRepositoryObjectReader() {
                this.readers = new HashSet<>();
            }

            public void addObjectReader(ObjectReader reader) {
                this.readers.add(reader);
            }

            @Override
            public Collection<ObjectId> resolve(AbbreviatedObjectId id) throws IOException {
                Set<ObjectId> result = new HashSet<>();
                for (ObjectReader reader : readers) {
                    result.addAll(reader.resolve(id));
                }
                return result;
            }

            @Override
            public ObjectLoader open(AnyObjectId objectId, int typeHint) throws IOException {
                for (ObjectReader reader : readers) {
                    if (reader.has(objectId, typeHint)) {
                        return reader.open(objectId, typeHint);
                    }
                }
                return null;
            }

            @Override
            public Set<ObjectId> getShallowCommits() throws IOException {
                Set<ObjectId> union = new HashSet<>();
                for (ObjectReader reader : readers) {
                    union.addAll(reader.getShallowCommits());
                }
                return union;
            }
        }

        final MultipleRepositoryObjectReader reader = new MultipleRepositoryObjectReader();
        reader.addObjectReader(repositoryA.newObjectReader());
        reader.addObjectReader(repositoryB.newObjectReader());

        @SuppressWarnings("rawtypes")
        Repository fakeRepo = new Repository(new BaseRepositoryBuilder()) {

            @Override
            public void create(boolean bare) throws IOException {
                throw new UnsupportedOperationException();
            }

            @Override
            public ObjectDatabase getObjectDatabase() {
                throw new UnsupportedOperationException();
            }

            @Override
            public RefDatabase getRefDatabase() {
                throw new UnsupportedOperationException();
            }

            @Override
            public StoredConfig getConfig() {
                return repositoryA.getConfig();
            }

            @Override
            public void scanForRepoChanges() throws IOException {
                throw new UnsupportedOperationException();
            }

            @Override
            public void notifyIndexChanged() {
                throw new UnsupportedOperationException();
            }

            @Override
            public ReflogReader getReflogReader(String refName) throws IOException {
                throw new UnsupportedOperationException();
            }

            public ObjectReader newObjectReader() {
                return reader;
            }
        };

        // formatter로 scan해야 rename detection 가능할 듯
        DiffFormatter formatter = new DiffFormatter(NullOutputStream.INSTANCE);
        formatter.setRepository(fakeRepo);
        formatter.setDetectRenames(true);

        AbstractTreeIterator treeParserA, treeParserB;
        RevTree treeA = null, treeB = null;

        if (commitA != null) {
            treeA = new RevWalk(repositoryA).parseTree(commitA);
            treeParserA = new CanonicalTreeParser();
            ((CanonicalTreeParser) treeParserA).reset(reader, treeA);
        } else {
            treeParserA = new EmptyTreeIterator();
        }

        if (commitB != null) {
            treeB = new RevWalk(repositoryB).parseTree(commitB);
            treeParserB = new CanonicalTreeParser();
            ((CanonicalTreeParser) treeParserB).reset(reader, treeB);
        } else {
            treeParserB = new EmptyTreeIterator();
        }

        List<FileDiff> result = new ArrayList<>();
        int size = 0;
        int lines = 0;

        for (DiffEntry diff : formatter.scan(treeParserA, treeParserB)) {
            FileDiff fileDiff = new FileDiff();
            fileDiff.commitA = commitA != null ? commitA.getName() : null;
            fileDiff.commitB = commitB != null ? commitB.getName() : null;

            fileDiff.changeType = diff.getChangeType();

            fileDiff.oldMode = diff.getOldMode();
            fileDiff.newMode = diff.getNewMode();

            String pathA = diff.getPath(DiffEntry.Side.OLD);
            String pathB = diff.getPath(DiffEntry.Side.NEW);

            byte[] rawA = null;
            if (treeA != null
                    && Arrays.asList(DELETE, MODIFY, RENAME, COPY).contains(diff.getChangeType())) {
                TreeWalk t1 = TreeWalk.forPath(repositoryA, pathA, treeA);
                ObjectId blobA = t1.getObjectId(0);
                fileDiff.pathA = pathA;

                try {
                    rawA = repositoryA.open(blobA).getBytes();
                    fileDiff.isBinaryA = RawText.isBinary(rawA);
                    fileDiff.a = fileDiff.isBinaryA ? null : new RawText(rawA);
                } catch (org.eclipse.jgit.errors.LargeObjectException e) {
                    fileDiff.addError(FileDiff.Error.A_SIZE_EXCEEDED);
                }
            }

            byte[] rawB = null;
            if (treeB != null
                    && Arrays.asList(ADD, MODIFY, RENAME, COPY).contains(diff.getChangeType())) {
                TreeWalk t2 = TreeWalk.forPath(repositoryB, pathB, treeB);
                ObjectId blobB = t2.getObjectId(0);
                fileDiff.pathB = pathB;

                try {
                    rawB = repositoryB.open(blobB).getBytes();
                    fileDiff.isBinaryB = RawText.isBinary(rawB);
                    fileDiff.b = fileDiff.isBinaryB ? null : new RawText(rawB);
                } catch (org.eclipse.jgit.errors.LargeObjectException e) {
                    fileDiff.addError(FileDiff.Error.B_SIZE_EXCEEDED);
                }
            }

            if (size > DIFF_SIZE_LIMIT || lines > DIFF_LINE_LIMIT) {
                fileDiff.addError(FileDiff.Error.OTHERS_SIZE_EXCEEDED);
                result.add(fileDiff);
                continue;
            }

            // Get diff if necessary
            if (fileDiff.a != null
                    && fileDiff.b != null
                    && !(fileDiff.isBinaryA || fileDiff.isBinaryB)
                    && Arrays.asList(MODIFY, RENAME).contains(diff.getChangeType())) {
                DiffAlgorithm diffAlgorithm = DiffAlgorithm.getAlgorithm(
                        repositoryB.getConfig().getEnum(
                                ConfigConstants.CONFIG_DIFF_SECTION, null,
                                ConfigConstants.CONFIG_KEY_ALGORITHM,
                                DiffAlgorithm.SupportedAlgorithm.HISTOGRAM));
                fileDiff.editList = diffAlgorithm.diff(RawTextComparator.DEFAULT, fileDiff.a,
                        fileDiff.b);
                size += fileDiff.getHunks().size;
                lines += fileDiff.getHunks().lines;
            }

            // update lines and sizes
            if (fileDiff.b != null && !fileDiff.isBinaryB && diff.getChangeType().equals(ADD)) {
                lines += fileDiff.b.size();
                size += rawB.length;
            }

            // update lines and sizes
            if (fileDiff.a != null && !fileDiff.isBinaryA && diff.getChangeType().equals(DELETE)) {
                lines += fileDiff.a.size();
                size += rawA.length;
            }

            // Stop if exceeds the limit for total number of files
            if (result.size() > DIFF_FILE_LIMIT) {
                break;
            }

            result.add(fileDiff);
        }

        return result;
    }

    /**
     * Clone a local repository.
     *
     * Do not copy but hardlink Git objects to save disk space.
     *
     * @param originalProject
     * @param forkProject
     * @throws IOException
     */
    protected static void cloneHardLinkedRepository(Project originalProject,
                                                    Project forkProject) throws IOException {
        Repository origin = GitRepository.buildGitRepository(originalProject);
        Repository forked = GitRepository.buildGitRepository(forkProject);
        forked.create();

        final Path originObjectsPath =
                Paths.get(new File(origin.getDirectory(), "objects").getAbsolutePath());
        final Path forkedObjectsPath =
                Paths.get(new File(forked.getDirectory(), "objects").getAbsolutePath());

        // Hardlink files .git/objects/ directory to save disk space,
        // but copy .git/info/alternates because the file can be modified.
        SimpleFileVisitor<Path> visitor =
                new SimpleFileVisitor<Path>() {
                    public FileVisitResult visitFile(Path file,
                                                     BasicFileAttributes attr) throws IOException {
                        Path newPath = forkedObjectsPath.resolve(
                                originObjectsPath.relativize(file.toAbsolutePath()));
                        if (file.equals(forkedObjectsPath.resolve("/info/alternates"))) {
                            Files.copy(file, newPath);
                        } else {
                            FileUtils.mkdirs(newPath.getParent().toFile(), true);
                            Files.createLink(newPath, file);
                        }
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                };
        Files.walkFileTree(originObjectsPath, visitor);

        // Import refs.
        for (Map.Entry<String, Ref> entry : origin.getAllRefs().entrySet()) {
            RefUpdate updateRef = forked.updateRef(entry.getKey());
            Ref ref = entry.getValue();
            if (ref.isSymbolic()) {
                updateRef.link(ref.getTarget().getName());
            } else {
                updateRef.setNewObjectId(ref.getObjectId());
                updateRef.update();
            }
        }
    }

    public GitBranch getHeadBranch() {
        try {
            String headBranchName = getDefaultBranch();

            ObjectId branchObjectId = repository.resolve(headBranchName);
            RevWalk walk = new RevWalk(repository);
            RevCommit head = walk.parseCommit(branchObjectId);
            walk.dispose();

            return new GitBranch(headBranchName, new GitCommit(head));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Clone과 Fetch 이후 작업에 필요한 정보를 담을 객체로 사용한다.
     */
    public static class CloneAndFetch {

        /**
         * 코드 받을 저장소의 코드를 non-bare 모드로 clone 받은 Git 저장소
         */
        private Repository repository;

        /**
         * 코드를 받을 브랜치의 코드를 fetch 받은 브랜치 이름
         */
        private String destToBranchName;

        /**
         * 코드를 보내는 브랜치의 코드를 fetch 받은 브랜치 이름
         */
        private String destFromBranchName;

        /**
         * 코드 받을 브랜치에서 생성한 브랜치로 실제 merge를 수행할 브랜치 이름
         * 겹치지 않도록 현재 시간을 이름으로 사용한다.
         */
        private String mergingBranchName;

        public Repository getRepository() {
            return repository;
        }

        public String getDestToBranchName() {
            return destToBranchName;
        }

        public String getDestFromBranchName() {
            return destFromBranchName;
        }

        public String getMergingBranchName() {
            return mergingBranchName;
        }

        private CloneAndFetch(Repository repository, String destToBranchName, String destFromBranchName, String mergingBranchName) {
            this.repository = repository;
            this.destToBranchName = destToBranchName;
            this.destFromBranchName = destFromBranchName;
            this.mergingBranchName = Constants.R_HEADS + mergingBranchName;
        }
    }

    /**
     * Clone과 Fetch 이후에 진행할 작업을 정의한다.
     *
     * @see #cloneAndFetch(models.PullRequest, playRepository.GitRepository.AfterCloneAndFetchOperation)
     */
    public static interface AfterCloneAndFetchOperation {
        public void invoke(CloneAndFetch cloneAndFetch) throws IOException, GitAPIException;
    }

    public void close() {
        repository.close();
    }

    /**
     * 코드저장소 프로젝트명을 변경하고 결과를 반환한다.
     *
     * 변경전 {@code repository.close()}를 통해 open된 repository의 리소스를 반환하고
     * repository 내부에서 사용하는 {@code WindowCache}를 초기화하여 packFile의 참조를 제거한다.
     *
     * @param projectName
     * @return 코드저장소 이름 변경성공시 true / 실패시 false
     * @see playRepository.PlayRepository#renameTo(String)
     */
    @Override
    public boolean renameTo(String projectName) {
        return move(this.ownerName, this.projectName, this.ownerName, projectName);
    }

    @Override
    public String getDefaultBranch() throws IOException {
        return repository.getRef(Constants.HEAD).getTarget().getName();
    }

    @Override
    public void setDefaultBranch(String target) throws IOException {
        Result result = repository.updateRef(Constants.HEAD).link(target);
        switch (result) {
        case NEW:
        case FORCED:
        case NO_CHANGE:
            break;
        default:
            throw new IOException("Failed to update symbolic ref, got: " + result);
        }
    }

    /**
     * {@code #commitIdString}에 해당하는 커밋의 부모 커밋 정보를 반환하다.
     *
     * @param commitIdString
     * @return
     */
    @Override
    public Commit getParentCommitOf(String commitIdString) {
        try {
            ObjectId commitId = repository.resolve(commitIdString);
            RevWalk revWalk = new RevWalk(repository);
            RevCommit commit = revWalk.parseCommit(commitId);
            if(commit.getParentCount() > 0) {
                ObjectId parentId = commit.getParent(0).getId();
                return new GitCommit(revWalk.parseCommit(parentId));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return null;
    }

    @Override
    public boolean isEmpty() {
        return this.getBranches().isEmpty();
    }

    /*
     * Find a Git object by revision.
     */
    private ObjectId getObjectId(String revstr) throws IOException {
        if (revstr == null) {
            return repository.resolve(Constants.HEAD);
        } else {
            return repository.resolve(revstr);
        }
    }

    /*
     * Find a RevCommit object by revision.
     */
    private RevCommit getRevCommit(String revstr) throws IOException {
        ObjectId objectId = getObjectId(revstr);
        return parseCommit(objectId);
    }

    /*
     * Find a RevCommit object by id.
     */
    private RevCommit parseCommit(AnyObjectId objectId) throws IOException {
        if (objectId == null) {
            return null;
        }
        RevWalk revWalk = new RevWalk(repository);
        return revWalk.parseCommit(objectId);
    }

    public boolean move(String srcProjectOwner, String srcProjectName, String desrProjectOwner, String destProjectName) {
        repository.close();
        WindowCacheConfig config = new WindowCacheConfig();
        config.install();

        File srcGitDirectory = new File(getGitDirectory(srcProjectOwner, srcProjectName));
        File destGitDirectory = new File(getGitDirectory(desrProjectOwner, destProjectName));
        File srcGitDirectoryForMerging = new File(getDirectoryForMerging(srcProjectOwner, srcProjectName));
        File destGitDirectoryForMerging = new File(getDirectoryForMerging(desrProjectOwner, destProjectName));
        srcGitDirectory.setWritable(true);
        srcGitDirectoryForMerging.setWritable(true);

        try {
            if(srcGitDirectory.exists()) {
                org.apache.commons.io.FileUtils.moveDirectory(srcGitDirectory, destGitDirectory);
                play.Logger.debug("[Transfer] Move from: " + srcGitDirectory.getAbsolutePath()
                        + "to " + destGitDirectory);
            } else {
                play.Logger.warn("[Transfer] Nothing to move from: " + srcGitDirectory.getAbsolutePath());
            }

            if(srcGitDirectoryForMerging.exists()) {
                org.apache.commons.io.FileUtils.moveDirectory(srcGitDirectoryForMerging, destGitDirectoryForMerging);
                play.Logger.debug("[Transfer] Move from: " + srcGitDirectoryForMerging.getAbsolutePath()
                        + "to " + destGitDirectoryForMerging);
            } else {
                play.Logger.warn("[Transfer] Nothing to move from: " + srcGitDirectoryForMerging.getAbsolutePath());
            }
            return true;
        } catch (IOException e) {
            play.Logger.error("[Transfer] Move Failed", e);
            return false;
        }
    }
}
