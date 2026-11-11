package com.javelin.ai;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A stand-in for the network: records what the external provider sends, answers from a script. */
final class ScriptedTransport implements ExternalTransport {
  record Call(String method, URI uri, Map<String, String> headers, String body) {}

  final List<Call> calls = new ArrayList<>();
  Response next = new Response(200, messageJson("ok", "end_turn", 10, 3));
  AiException failWith;

  static String messageJson(String text, String stop, int in, int out) {
    return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":"
        + com.javelin.language.Json.write(text)
        + "}],\"stop_reason\":\""
        + stop
        + "\",\"usage\":{\"input_tokens\":"
        + in
        + ",\"output_tokens\":"
        + out
        + "}}";
  }

  @Override
  public Response get(URI uri, Map<String, String> headers, Duration timeout, AiCancel cancel)
      throws AiException {
    return answer(new Call("GET", uri, headers, null), cancel);
  }

  @Override
  public Response post(
      URI uri, Map<String, String> headers, String body, Duration timeout, AiCancel cancel)
      throws AiException {
    return answer(new Call("POST", uri, headers, body), cancel);
  }

  private Response answer(Call call, AiCancel cancel) throws AiException {
    cancel.throwIfCancelled();
    calls.add(call);
    if (failWith != null) throw failWith;
    return next;
  }
}
