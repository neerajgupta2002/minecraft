package ai.xelta.craft.generation;

import java.util.List;

public class SemanticModel {
    public record Scene(List<SemanticObject> objects, String style, String scale) {}
    public record SemanticObject(String type, String position, double scale, String material) {}
}
