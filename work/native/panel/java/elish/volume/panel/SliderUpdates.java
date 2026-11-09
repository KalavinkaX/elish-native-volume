package elish.volume.panel;

/** User input lifetime is separate from the audio daemon lifetime. */
final class SliderUpdates {
    interface Scheduler { void post(Runnable task,long delay); void remove(Runnable task); }
    interface Sink { void send(int level); }
    final Scheduler scheduler;final Sink sink;
    boolean closed,scheduled,changed;
    int latest;
    final Runnable task=new Runnable(){public void run(){scheduled=false;if(!closed&&changed)sink.send(latest);}};
    SliderUpdates(Scheduler scheduler,Sink sink){this.scheduler=scheduler;this.sink=sink;}
    void change(int level){
        if(closed)return;
        if(level<0||level>100)throw new IllegalArgumentException("Invalid slider value");
        latest=level;changed=true;
        if(!scheduled){scheduled=true;scheduler.post(task,80);}
    }
    void finish(){
        if(closed||!changed)return;
        scheduler.remove(task);scheduled=false;sink.send(latest);changed=false;
    }
    void close(){closed=true;scheduler.remove(task);scheduled=false;}
}
