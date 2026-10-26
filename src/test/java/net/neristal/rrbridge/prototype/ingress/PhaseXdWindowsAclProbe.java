package net.neristal.rrbridge.prototype.ingress;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/**
 * Phase X-D2 read-only Windows ACL PoC (test code only; no product API, no gate, never PASS).
 *
 * <p>Reads owner and DACL (allow/deny, flags, permissions) through {@link AclFileAttributeView} of a NEW owned NTFS fixture
 * (directory + file + nested JAR-like file) and, read-only, of every real parent directory up to the volume root. Nothing
 * is ever written to an ACL. Each object is classified with the provisional rules of the X-C owner dossier §4:
 * <ul>
 * <li>{@code MISMATCH} — a broad principal (Everyone, BUILTIN\Users, NT AUTHORITY\Authenticated Users) or any other
 *     resolved principal than the current user / SYSTEM / Administrators holds a write-class right in an ALLOW entry;</li>
 * <li>{@code UNKNOWN} — a DENY entry (ordering semantics), an unresolved/unlisted principal kind, a non-ALLOW/DENY entry
 *     type, or a read failure;</li>
 * <li>{@code CANDIDATE_IF_SYSTEM_AND_ADMINS_TRUSTED} — only the current user, SYSTEM and Administrators hold write-class
 *     rights. Whether SYSTEM/Administrators are trusted is OWNER_DECISION_REQUIRED, so this is not OWNER_CONTROLLED.</li>
 * </ul>
 * Effective rights (group membership, privileges such as SeBackup/SeRestore/SeTakeOwnership, conditional ACEs) are NOT
 * evaluated: that needs native AuthzAccessCheck (JNA; licence decision first). Therefore the overall result is always
 * {@code UNKNOWN_EFFECTIVE_RIGHTS_NOT_EVALUATED} at best. Classification of synthetic in-memory ACLs is tested separately
 * (no real ACL is changed to produce them).
 */
public final class PhaseXdWindowsAclProbe {
    static int checks;
    static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);checks++;System.out.println("PASS "+m);}
    static final Set<AclEntryPermission> WRITE_CLASS=Collections.unmodifiableSet(EnumSet.of(AclEntryPermission.WRITE_DATA,AclEntryPermission.APPEND_DATA,
        AclEntryPermission.WRITE_ATTRIBUTES,AclEntryPermission.WRITE_NAMED_ATTRS,AclEntryPermission.DELETE,AclEntryPermission.DELETE_CHILD,
        AclEntryPermission.WRITE_ACL,AclEntryPermission.WRITE_OWNER));

    /** Principal kinds resolved by name lookup (read-only). A lookup failure leaves the kind unresolvable -> UNKNOWN. */
    static final class Principals {
        final UserPrincipal me,system,admins,everyone,users,authenticated;
        Principals(UserPrincipalLookupService l,UserPrincipal me){
            this.me=me;system=find(l,"NT AUTHORITY\\SYSTEM");admins=find(l,"BUILTIN\\Administrators");everyone=find(l,"Everyone");
            users=find(l,"BUILTIN\\Users");authenticated=find(l,"NT AUTHORITY\\Authenticated Users");
        }
        static UserPrincipal find(UserPrincipalLookupService l,String n){try{return l.lookupPrincipalByName(n);}catch(IOException|RuntimeException absent){return null;}}
        String kind(UserPrincipal p){
            if(p==null)return "UNRESOLVED";if(p.equals(me))return "CURRENT_USER";if(p.equals(system))return "SYSTEM";if(p.equals(admins))return "ADMINISTRATORS";
            if(p.equals(everyone)||p.equals(users)||p.equals(authenticated))return "BROAD_GROUP";
            // An unresolved SID is printed by the JDK as its S-1-... string.
            return p.getName().startsWith("S-1-")?"UNRESOLVED_SID":"OTHER_PRINCIPAL";
        }
    }
    static final class Verdict {final String status;final Set<String> codes;Verdict(String s,Set<String> c){status=s;codes=c;}public String toString(){return status+" "+codes;}}

    /** Provisional dossier §4 classification of one object's owner + DACL (facts only; no trust decision is taken). */
    static Verdict classify(UserPrincipal owner,List<AclEntry> acl,Principals p){
        Set<String> codes=new TreeSet<String>();boolean mismatch=false,unknown=false;
        String ok=p.kind(owner);codes.add("OWNER_"+ok);
        if(!ok.equals("CURRENT_USER")&&!ok.equals("ADMINISTRATORS")&&!ok.equals("SYSTEM")){if(ok.startsWith("UNRESOLVED"))unknown=true;else mismatch=true;}
        if(!ok.equals("CURRENT_USER"))codes.add("OWNER_NOT_CURRENT_USER");
        for(AclEntry e:acl){
            String k=p.kind(e.principal());boolean write=!Collections.disjoint(e.permissions(),WRITE_CLASS);
            if(e.flags().contains(AclEntryFlag.INHERIT_ONLY))codes.add("INHERIT_ONLY_ENTRY");
            if(e.type()==AclEntryType.DENY){codes.add("DENY_ENTRY_ORDERING_NOT_EVALUATED");unknown=true;continue;}
            if(e.type()!=AclEntryType.ALLOW){codes.add("NON_ALLOW_DENY_ENTRY");unknown=true;continue;}
            if(!write)continue;
            codes.add("WRITE_"+k);
            if(k.equals("BROAD_GROUP")||k.equals("OTHER_PRINCIPAL"))mismatch=true;
            else if(k.startsWith("UNRESOLVED"))unknown=true;
            else if(!k.equals("CURRENT_USER"))codes.add("TRUST_OF_"+k+"_OWNER_DECISION_REQUIRED");
        }
        return new Verdict(mismatch?"MISMATCH":unknown?"UNKNOWN":"CANDIDATE_IF_SYSTEM_AND_ADMINS_TRUSTED",codes);
    }
    static Verdict read(Path p,Principals pr){
        try{AclFileAttributeView v=Files.getFileAttributeView(p,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
            if(v==null)return new Verdict("UNKNOWN",new TreeSet<String>(Collections.singleton("ACL_VIEW_UNAVAILABLE")));
            return classify(v.getOwner(),v.getAcl(),pr);}
        catch(IOException|SecurityException failed){return new Verdict("UNKNOWN",new TreeSet<String>(Collections.singleton("ACL_READ_FAILED")));}
    }

    public static void main(String[] a)throws Exception {
        if(!System.getProperty("os.name").startsWith("Windows")){System.out.println("NOT_RUN Windows ACL probe (not Windows)");return;}
        // Plain temp directory: its ACL is the inherited default and is NEVER set by this probe (no privateDir).
        Path home=Files.createTempDirectory("romlogger-phasexd-acl-");
        try{
            Path dir=Files.createDirectory(home.resolve("install")),lib=Files.createDirectory(dir.resolve("lib")),jar=Files.write(lib.resolve("synthetic.jar"),new byte[]{0x50,0x4b,5,6});
            check(Files.getFileStore(home).type().equalsIgnoreCase("NTFS"),"owned fixture is on NTFS ("+Files.getFileStore(home).type()+")");
            UserPrincipalLookupService l=home.getFileSystem().getUserPrincipalLookupService();
            Principals pr=new Principals(l,Files.getOwner(jar,LinkOption.NOFOLLOW_LINKS));
            check(pr.system!=null&&pr.admins!=null&&pr.everyone!=null&&pr.users!=null&&pr.authenticated!=null,"well-known principals resolved by name (read-only lookup)");

            // ---- real, unmodified ACLs: owned fixture objects, then every real parent up to the volume root (read only).
            for(Path p:new Path[]{jar,lib,dir,home}){Verdict v=read(p,pr);System.out.println("OBSERVED fixture/"+home.relativize(p)+" "+v);
                check(!"MISMATCH".equals(v.status),"owned fixture "+(p==home?"home":home.relativize(p))+": no broad/other write principal on a default inherited ACL");}
            int depth=0;String parentStatus="CANDIDATE_IF_SYSTEM_AND_ADMINS_TRUSTED";
            for(Path q=home.getParent();q!=null;q=q.getParent()){depth++;Verdict v=read(q,pr);System.out.println("OBSERVED parent-"+depth+" "+v);
                if("MISMATCH".equals(v.status))parentStatus="MISMATCH";else if("UNKNOWN".equals(v.status)&&!"MISMATCH".equals(parentStatus))parentStatus="UNKNOWN";}
            System.out.println("OBSERVED parent_chain depth="+depth+" status="+parentStatus);
            check(depth>=1,"parent chain inspected read-only up to the volume root ("+depth+" levels)");

            // ---- classification of synthetic in-memory ACLs (no real ACL is changed).
            AclEntry meFull=entry(AclEntryType.ALLOW,pr.me,AclEntryPermission.values());
            check("CANDIDATE_IF_SYSTEM_AND_ADMINS_TRUSTED".equals(classify(pr.me,Arrays.asList(meFull),pr).status),"only the current user writes: candidate, still not OWNER_CONTROLLED");
            Verdict sys=classify(pr.me,Arrays.asList(meFull,entry(AclEntryType.ALLOW,pr.system,AclEntryPermission.values())),pr);
            check(sys.codes.contains("TRUST_OF_SYSTEM_OWNER_DECISION_REQUIRED"),"SYSTEM write right is flagged OWNER_DECISION_REQUIRED "+sys);
            check("MISMATCH".equals(classify(pr.me,Arrays.asList(meFull,entry(AclEntryType.ALLOW,pr.users,AclEntryPermission.APPEND_DATA)),pr).status),"BUILTIN\\Users APPEND_DATA -> MISMATCH");
            check("MISMATCH".equals(classify(pr.me,Arrays.asList(entry(AclEntryType.ALLOW,pr.everyone,AclEntryPermission.WRITE_DATA)),pr).status),"Everyone WRITE_DATA -> MISMATCH");
            check("MISMATCH".equals(classify(pr.me,Arrays.asList(entry(AclEntryType.ALLOW,pr.authenticated,AclEntryPermission.DELETE_CHILD)),pr).status),"Authenticated Users DELETE_CHILD (parent rename/replace) -> MISMATCH");
            check("CANDIDATE_IF_SYSTEM_AND_ADMINS_TRUSTED".equals(classify(pr.me,Arrays.asList(meFull,entry(AclEntryType.ALLOW,pr.users,AclEntryPermission.READ_DATA,AclEntryPermission.EXECUTE)),pr).status),"Users read/execute only -> no write-class finding");
            check("UNKNOWN".equals(classify(pr.me,Arrays.asList(entry(AclEntryType.DENY,pr.everyone,AclEntryPermission.WRITE_DATA),meFull),pr).status),"any DENY entry -> UNKNOWN (ordering/effective rights not evaluated)");
            check("UNKNOWN".equals(classify(pr.me,Arrays.asList(entry(AclEntryType.AUDIT,pr.everyone,AclEntryPermission.WRITE_DATA),meFull),pr).status),"non ALLOW/DENY entry -> UNKNOWN");
            check("MISMATCH".equals(classify(pr.users,Arrays.asList(meFull),pr).status),"owner = BUILTIN\\Users -> MISMATCH");
            check(classify(pr.admins,Arrays.asList(meFull),pr).codes.contains("OWNER_NOT_CURRENT_USER"),"owner = Administrators is reported (OWNER_NOT_CURRENT_USER)");
            check("UNKNOWN".equals(classify(null,Arrays.asList(meFull),pr).status),"unresolved owner -> UNKNOWN");
            System.out.println("OBSERVED overall=UNKNOWN_EFFECTIVE_RIGHTS_NOT_EVALUATED gate_use=OWNER_DECISION_REQUIRED");
            System.out.println("PHASE_XD_ACL_PROBE_CHECKS "+checks);
        }finally{removeTree(home);}
    }
    static AclEntry entry(AclEntryType t,UserPrincipal p,AclEntryPermission... perms){return AclEntry.newBuilder().setType(t).setPrincipal(p).setPermissions(perms).build();}
    static void removeTree(final Path h)throws IOException {
        Files.walkFileTree(h,new SimpleFileVisitor<Path>(){
            public FileVisitResult visitFile(Path f,BasicFileAttributes x)throws IOException{Files.delete(f);return FileVisitResult.CONTINUE;}
            public FileVisitResult postVisitDirectory(Path d,IOException e)throws IOException{Files.delete(d);return FileVisitResult.CONTINUE;}});
    }
}
