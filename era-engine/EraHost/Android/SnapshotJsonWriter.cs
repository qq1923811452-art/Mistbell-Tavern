using MinorShift.Emuera.Output;
using System.Buffers;
using System.Text;
using System.Text.Json;

namespace Mistbell.Era.Host;

internal static class SnapshotJsonWriter
{
    public static string SerializeEnvelope(DisplaySnapshot snapshot)
    {
        var buffer = new ArrayBufferWriter<byte>(8192);
        using var w = new Utf8JsonWriter(buffer);
        w.WriteStartObject();
        w.WriteString("type", "snapshot");
        w.WritePropertyName("payload");
        WriteSnapshot(w, snapshot);
        w.WriteEndObject();
        w.Flush();
        return Encoding.UTF8.GetString(buffer.WrittenSpan);
    }

    private static void WriteSnapshot(Utf8JsonWriter w, DisplaySnapshot s)
    {
        w.WriteStartObject();
        w.WriteInt32("protocolVersion", s.protocolVersion);
        w.WriteInt64("generation", s.generation);
        w.WriteInt64("revision", s.revision);
        if (s.bgColor != null)
            w.WriteString("bgColor", s.bgColor);

        w.WritePropertyName("awaiting");
        WriteAwaitInfo(w, s.awaiting);

        w.WritePropertyName("lines");
        w.WriteStartArray();
        foreach (var line in s.lines)
            WriteLine(w, line);
        w.WriteEndArray();

        if (s.bgImages != null)
        {
            w.WritePropertyName("bgImages");
            w.WriteStartArray();
            foreach (var bg in s.bgImages)
            {
                w.WriteStartObject();
                w.WriteString("src", bg.src);
                w.WriteNumber("depth", bg.depth);
                w.WriteNumber("opacity", bg.opacity);
                w.WriteEndObject();
            }
            w.WriteEndArray();
        }

        w.WriteEndObject();
    }

    private static void WriteAwaitInfo(Utf8JsonWriter w, AwaitInfo a)
    {
        w.WriteStartObject();
        if (a.input != null)
        {
            w.WritePropertyName("input");
            w.WriteStartObject();
            w.WriteString("type", a.input.type);
            w.WriteEndObject();
        }
        if (a.wait != null)
        {
            w.WritePropertyName("wait");
            w.WriteStartObject();
            w.WriteInt64("durationMs", a.wait.durationMs);
            if (a.wait.displayTime == true)
                w.WriteBoolean("displayTime", true);
            if (a.wait.timeUpMessage != null)
                w.WriteString("timeUpMessage", a.wait.timeUpMessage);
            if (a.wait.timedOut == true)
                w.WriteBoolean("timedOut", true);
            w.WriteEndObject();
        }
        if (a.ended != null)
        {
            w.WritePropertyName("ended");
            w.WriteStartObject();
            w.WriteString("terminal", a.ended.terminal);
            w.WriteEndObject();
        }
        w.WriteEndObject();
    }

    private static void WriteLine(Utf8JsonWriter w, DisplayLine line)
    {
        w.WriteStartObject();
        if (line.align != null)
            w.WriteString("align", line.align);
        w.WriteBoolean("isLineEnd", line.isLineEnd);
        w.WritePropertyName("entries");
        w.WriteStartArray();
        foreach (var entry in line.entries)
        {
            w.WriteStartObject();
            if (entry.button != null)
            {
                w.WritePropertyName("button");
                WriteButton(w, entry.button);
            }
            w.WritePropertyName("segments");
            w.WriteStartArray();
            foreach (var seg in entry.segments)
                WriteSegment(w, seg);
            w.WriteEndArray();
            w.WriteEndObject();
        }
        w.WriteEndArray();
        w.WriteEndObject();
    }

    private static void WriteButton(Utf8JsonWriter w, ButtonRef b)
    {
        w.WriteStartObject();
        w.WritePropertyName("value");
        switch (b.value)
        {
            case long l:
                w.WriteNumberValue(l);
                break;
            case int i:
                w.WriteNumberValue(i);
                break;
            case string str:
                w.WriteStringValue(str);
                break;
            default:
                w.WriteStringValue(b.value?.ToString() ?? string.Empty);
                break;
        }
        w.WriteBoolean("isInteger", b.isInteger);
        w.WriteInt64("generation", b.generation);
        if (b.col.HasValue)
            w.WriteNumber("col", b.col.Value);
        if (b.width.HasValue)
            w.WriteNumber("width", b.width.Value);
        w.WriteEndObject();
    }

    private static void WriteSegment(Utf8JsonWriter w, PrintSegment seg)
    {
        w.WriteStartObject();
        w.WriteString("text", seg.text);
        if (seg.color != null)
            w.WriteString("color", seg.color);
        if (seg.bold == true)
            w.WriteBoolean("bold", true);
        if (seg.italic == true)
            w.WriteBoolean("italic", true);
        if (seg.underline == true)
            w.WriteBoolean("underline", true);
        if (seg.strikeout == true)
            w.WriteBoolean("strikeout", true);
        if (seg.fontname != null)
            w.WriteString("fontname", seg.fontname);
        if (seg.image != null)
        {
            w.WritePropertyName("image");
            w.WriteStartObject();
            w.WriteString("src", seg.image.src);
            if (seg.image.srcb != null)
                w.WriteString("srcb", seg.image.srcb);
            if (seg.image.srcm != null)
                w.WriteString("srcm", seg.image.srcm);
            w.WriteInt32("width", seg.image.width);
            w.WriteInt32("height", seg.image.height);
            w.WriteInt32("ypos", seg.image.ypos);
            w.WriteEndObject();
        }
        if (seg.shape != null)
        {
            w.WritePropertyName("shape");
            w.WriteStartObject();
            w.WriteString("type", seg.shape.type);
            w.WriteInt32("x", seg.shape.x);
            w.WriteInt32("y", seg.shape.y);
            w.WriteInt32("width", seg.shape.width);
            w.WriteInt32("height", seg.shape.height);
            w.WriteString("color", seg.shape.color);
            w.WriteEndObject();
        }
        w.WriteEndObject();
    }
}
