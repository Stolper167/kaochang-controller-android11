package cn.niuyannet.kaochang.android.mqtt;

public class TopicMessage {
    public int action;// 1更新下单进度
    public String content;//json内容

    public String getContent() {
        return content;
    }
    public void setContent(String content) {
        this.content = content;
    }
    public int getAction() {
        return action;
    }
    public void setAction(int action) {
        this.action = action;
    }
}
