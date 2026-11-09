package android.content.pm;
import java.util.*;
import android.os.IBinder;
public interface IPackageManager {
    int getPackageUid(String pkg, long flags, int user);
    String[] getPackagesForUid(int uid);
    public static class Stub {
        public static Map<String,Integer> uids=new HashMap<String,Integer>();
        public static IPackageManager asInterface(IBinder binder) {
            return new IPackageManager() { public int getPackageUid(String pkg,long flags,int user) {
                Integer uid=uids.get(user+":"+pkg); return uid==null ? -1 : uid;
            }
            public String[] getPackagesForUid(int uid) {
                java.util.List<String> list=new java.util.ArrayList<String>();
                for(java.util.Map.Entry<String,Integer> e:uids.entrySet()) if(e.getValue()==uid)list.add(e.getKey().substring(e.getKey().indexOf(':')+1));
                return list.toArray(new String[0]);
            }};
        }
    }
}
