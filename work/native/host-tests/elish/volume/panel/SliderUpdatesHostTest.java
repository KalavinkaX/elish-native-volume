package elish.volume.panel;
import java.util.*;
public final class SliderUpdatesHostTest {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static class Queue implements SliderUpdates.Scheduler {
        Runnable task,removed;
        public void post(Runnable t,long delay){task=t;check(delay<=100,"drag update unexpectedly delayed");}
        public void remove(Runnable t){removed=t;if(task==t)task=null;}
        void run(){Runnable t=task;task=null;if(t!=null)t.run();}
    }
    public static void main(String[] args){
        Queue queue=new Queue();List<Integer> sent=new ArrayList<Integer>();
        SliderUpdates input=new SliderUpdates(queue,level->sent.add(level));
        if(args[0].equals("close-pending")){
            input.change(35);Runnable late=queue.task;input.close();late.run();input.finish();input.change(0);
            check(sent.isEmpty(),"closing/detaching emitted a volume update");
        }else if(args[0].equals("untouched-close")){
            input.finish();input.close();check(sent.isEmpty(),"opening and closing wrote an implicit zero");
        }else if(args[0].equals("latest-final")){
            input.change(70);input.change(20);input.change(35);queue.run();
            check(sent.equals(Arrays.asList(35)),"old touch values were queued");
            input.change(15);input.finish();queue.run();
            check(sent.equals(Arrays.asList(35,15)),"final value lost or duplicated");
            input.close();input.finish();check(sent.equals(Arrays.asList(35,15)),"close changed last volume");
        }else throw new AssertionError("Unknown scenario");
    }
}
