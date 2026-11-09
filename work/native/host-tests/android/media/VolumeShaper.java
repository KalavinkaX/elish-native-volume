package android.media;
public final class VolumeShaper {
    public static final class Configuration {
        public int id,interpolator;
        public long duration;
        public float[] times,volumes;
        public Configuration(int id){this.id=id;}
        public static class Builder {
            final Configuration c=new Configuration(-1);
            public Builder setId(int id){c.id=id;return this;}
            public Builder setInterpolatorType(int type){c.interpolator=type;return this;}
            public Builder setDuration(long duration){c.duration=duration;return this;}
            public Builder setCurve(float[] times,float[] volumes){c.times=times;c.volumes=volumes;return this;}
            public Configuration build(){return c;}
        }
    }
    public static final class Operation {
        public boolean terminate,join;
        public int replaceId=-1;
        public float offset;
        public static class Builder {
            final Operation o=new Operation();
            public Builder terminate(){o.terminate=true;return this;}
            public Builder replace(int id,boolean join){o.replaceId=id;o.join=join;return this;}
            public Builder setXOffset(float offset){o.offset=offset;return this;}
            public Operation build(){return o;}
        }
    }
}
