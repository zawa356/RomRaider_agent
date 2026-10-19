package net.neristal.rrbridge.product.v1;
import java.io.*;
import java.nio.file.*;
public final class DeploymentHardlinkAliasTest {
 public static void main(String[] args)throws Exception {
  DeploymentBinding.Layout first=DeploymentBindingTest.fixture();DeploymentBinding.bindSynthetic(true,true,first);
  File alias=first.legacy.toPath().resolveSibling("old-alias.db").toFile();Files.createLink(alias.toPath(),first.legacy.toPath());
  if(!Files.isSameFile(alias.toPath(),first.legacy.toPath()))throw new AssertionError("hardlink fixture failed");
  File second=DeploymentBindingTest.privateDir(Files.createDirectory(first.root.toPath().resolveSibling("second-root"))).toFile();
  DeploymentBinding.Layout duplicate=DeploymentBindingTest.with(first,second,alias,first.output,first.session,first.manifest);
  boolean rejected=false;try{DeploymentBinding.bindSynthetic(true,true,duplicate);}catch(IOException expected){rejected="HARDLINK_ALIAS".equals(expected.getMessage());}
  if(!rejected)throw new AssertionError("HARDLINK_ALIAS: same physical legacy DB enrolled under two roots and lock paths");
  if(Files.exists(second.toPath().resolve(DeploymentBinding.NAME))||Files.exists(Paths.get(alias+".deployment-binding")))throw new AssertionError("unsafe alias wrote enrollment evidence");
  System.out.println("PASS hardlink DB alias refuses before alternate-root enrollment writes");
 }
}
