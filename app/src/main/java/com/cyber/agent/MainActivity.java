package com.cyber.agent;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private EditText etInput;
    private Spinner spinnerModel;
    private Switch switchThinking, switchShellMode;
    private boolean isRunning = false;
    private OkHttpClient client;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MsgAdapter adapter;
    private final List<Msg> msgList = new ArrayList<>();

    private final String API_URL = "http://127.0.0.1:5001/v1/chat/completions";
    private final String API_KEY = "sk-cyberdeep";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recyclerView = findViewById(R.id.recyclerView);
        etInput = findViewById(R.id.etInput);
        spinnerModel = findViewById(R.id.spinnerModel);
        switchThinking = findViewById(R.id.switchThinking);
        switchShellMode = findViewById(R.id.switchShellMode);
        DrawerLayout drawerLayout = findViewById(R.id.drawerLayout);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);

        // 侧边栏控制
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_sort_by_size);
        toolbar.setNavigationOnClickListener(v -> drawerLayout.openDrawer(findViewById(R.id.spinnerModel).getRootView().findViewById(android.R.id.content).getParent() == null ? null : (View)findViewById(R.id.spinnerModel).getParent()));

        String[] models = {"deepseek-v4-flash", "deepseek-v4-pro", "deepseek-v4-flash-search"};
        ArrayAdapter<String> adapterModels = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, models);
        adapterModels.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerModel.setAdapter(adapterModels);

        adapter = new MsgAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        client = new OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build();

        findViewById(R.id.btnSend).setOnClickListener(v -> {
            String prompt = etInput.getText().toString().trim();
            if (TextUtils.isEmpty(prompt) || isRunning) return;
            etInput.setText("");
            
            if (switchShellMode.isChecked()) {
                // 直接执行 Root 命令模式
                addMsg("User (Shell)", prompt);
                runRootCommand(prompt);
            } else {
                // AI 对话模式
                addMsg("User", prompt);
                callAI(prompt);
            }
        });
    }

    private void callAI(String prompt) {
        isRunning = true;
        String model = spinnerModel.getSelectedItem().toString();
        boolean useReasoning = switchThinking.isChecked();

        String sysPrompt = "你是一个运行在安卓手机上的高级开发助手。你能读取日志、分析代码、执行 Shell。" +
                "如果需要用户执行命令，请务必使用 ```bash 代码块包裹。" +
                "当前模型：" + model;

        try {
            JSONObject json = new JSONObject();
            json.put("model", model);
            json.put("stream", true);
            
            JSONArray messages = new JSONArray();
            JSONObject sys = new JSONObject(); sys.put("role", "system"); sys.put("content", sysPrompt); messages.put(sys);
            JSONObject user = new JSONObject(); user.put("role", "user"); user.put("content", prompt); messages.put(user);
            json.put("messages", messages);

            RequestBody body = RequestBody.create(json.toString(), MediaType.parse("application/json"));
            Request request = new Request.Builder().url(API_URL).addHeader("Authorization", "Bearer " + API_KEY).post(body).build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    mainHandler.post(() -> { addMsg("System", "❌ 请求失败: " + e.getMessage()); isRunning = false; });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        mainHandler.post(() -> { addMsg("System", "❌ HTTP " + response.code()); isRunning = false; });
                        return;
                    }
                    if (response.body() != null) {
                        okio.BufferedSource source = response.body().source();
                        StringBuilder fullText = new StringBuilder();
                        try {
                            while (!source.exhausted()) {
                                String line = source.readUtf8Line();
                                if (line != null && line.startsWith("data: ") && !line.equals("data: [DONE]")) {
                                    JSONObject chunk = new JSONObject(line.substring(6));
                                    JSONArray choices = chunk.optJSONArray("choices");
                                    if (choices != null && choices.length() > 0) {
                                        JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                                        if (delta != null) {
                                            if (delta.has("reasoning_content") && useReasoning) {
                                                String think = delta.getString("reasoning_content");
                                                mainHandler.post(() -> appendAiText("🧠 " + think, true));
                                            }
                                            if (delta.has("content")) {
                                                String text = delta.getString("content");
                                                if (!text.isEmpty()) {
                                                    fullText.append(text);
                                                    mainHandler.post(() -> appendAiText(text, false));
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                        // 完成后检查是否包含 bash 代码块
                        mainHandler.post(() -> checkForCodeBlock(fullText.toString()));
                    }
                    mainHandler.post(() -> isRunning = false);
                }
            });
        } catch (Exception e) {
            addMsg("System", "组装异常: " + e.getMessage());
            isRunning = false;
        }
    }

    // 检查 AI 回复里有没有 bash 代码块，如果有，显示执行按钮
    private void checkForCodeBlock(String fullText) {
        Pattern pattern = Pattern.compile("```bash\\s*([\\s\\S]*?)```");
        Matcher matcher = pattern.matcher(fullText);
        if (matcher.find()) {
            String script = matcher.group(1).trim();
            addMsg("System (检测到可执行代码)", script);
        }
    }

    // 核心：用 Root 执行 Shell
    private void runRootCommand(String cmd) {
        isRunning = true;
        addMsg("System", "执行中...");
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                p.waitFor();
                final String result = output.toString();
                mainHandler.post(() -> {
                    addMsg("Root Output", result);
                    isRunning = false;
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    addMsg("System", "执行报错: " + e.getMessage());
                    isRunning = false;
                });
            }
        }).start();
    }

    private Msg currentAiMsg = null;
    private void appendAiText(String text, boolean isThought) {
        if (currentAiMsg == null || currentAiMsg.role.equals("User") || currentAiMsg.role.equals("User (Shell)")) {
            currentAiMsg = new Msg(isThought ? "AI (思考)" : "AI", "");
            msgList.add(currentAiMsg);
            adapter.notifyItemInserted(msgList.size() - 1);
        }
        currentAiMsg.content += text;
        adapter.notifyItemChanged(msgList.size() - 1);
        recyclerView.smoothScrollToPosition(msgList.size() - 1);
    }

    private void addMsg(String role, String content) {
        currentAiMsg = null; // 重置当前气泡
        msgList.add(new Msg(role, content));
        adapter.notifyItemInserted(msgList.size() - 1);
        recyclerView.smoothScrollToPosition(msgList.size() - 1);
    }

    class MsgAdapter extends RecyclerView.Adapter<MsgAdapter.VH> {
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH holder, int position) {
            Msg msg = msgList.get(position);
            holder.tvRole.setText(msg.role);
            holder.tvContent.setText(msg.content);

            if ("User".equals(msg.role) || "User (Shell)".equals(msg.role)) {
                holder.tvRole.setTextColor(0xFF00E5FF);
                holder.tvContent.setBackgroundColor(0xFF1E3A8A);
            } else if (msg.role.contains("思考")) {
                holder.tvRole.setTextColor(0xFF9CA3AF);
                holder.tvContent.setBackgroundColor(0xFF374151);
                holder.tvContent.setTextColor(0xFF9CA3AF);
            } else if ("Root Output".equals(msg.role)) {
                holder.tvRole.setTextColor(0xFFEF4444);
                holder.tvContent.setBackgroundColor(0xFF000000);
                holder.tvContent.setTextColor(0xFF10B981);
            } else {
                holder.tvRole.setTextColor(0xFF10B981);
                holder.tvContent.setBackgroundColor(0xFF1E293B);
                holder.tvContent.setTextColor(0xFFE2E8F0);
            }

            // 如果是可执行代码，显示按钮
            if (msg.role.contains("可执行代码")) {
                holder.btnExecCode.setVisibility(View.VISIBLE);
                holder.btnExecCode.setOnClickListener(v -> runRootCommand(msg.content));
            } else {
                holder.btnExecCode.setVisibility(View.GONE);
            }
        }
        @Override public int getItemCount() { return msgList.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView tvRole, tvContent;
            com.google.android.material.button.MaterialButton btnExecCode;
            VH(View v) {
                super(v);
                tvRole = v.findViewById(R.id.tvRole);
                tvContent = v.findViewById(R.id.tvContent);
                btnExecCode = v.findViewById(R.id.btnExecCode);
            }
        }
    }
    static class Msg { String role, content; Msg(String role, String content) { this.role = role; this.content = content; } }
}
