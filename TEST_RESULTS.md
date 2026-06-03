# Test Results

## Build

```text
javac -source 1.8 -target 1.8 -cp javaparser-core-3.26.4.jar:javaparser-symbol-solver-core-3.26.4.jar ...
SUCCESS
```

Die Quellen kompilieren weiterhin mit Java-8-Target. Der neue `./build.sh` nutzt Gradle und lädt JavaParser über Maven Central. Dieser Gradle-Download wurde in der Sandbox nicht ausgeführt, weil dort kein Maven-Central-Zugriff verfügbar ist.

## HelloAI: Rename

Befehl:

```bash
dist/ai-java-refactor rename-plan \
  --project /mnt/data/helloai/HelloAI-master \
  --symbol 'org.example.BallPanel#moveBall()' \
  --new-name updateBallPosition
```

Ergebnis:

```text
status: SAFE
parsedFiles: 2
resolvedUsages: 2
edits: BallPanel.moveBall declaration + BallAnimator call
```

`rename-apply` wurde auf einer Kopie ausgeführt. Anschließend kompilierte das Projekt mit `javac`.

## Extract Class: kontrolliertes Sample

Quelle:

```java
package org.example;

public class Sample {
    private int x = 1;
    private int y = 2;

    public int sum() {
        return x + y;
    }

    public void reset() {
        x = 0;
        y = 0;
    }
}
```

Befehl:

```bash
dist/ai-java-refactor extract-class-apply \
  --project /mnt/data/extract-sample \
  --source-class org.example.Sample \
  --target-class SampleState \
  --delegate sampleState \
  --members 'field:x,field:y'
```

Ergebnis:

```text
status: SAFE
created: SampleState.java
rewrites: getX/getY/setX/setY
javac: SUCCESS
```

## HelloAI: Extract Class bewusst konservativ

Befehl:

```bash
dist/ai-java-refactor extract-class-plan \
  --project /mnt/data/helloai/HelloAI-master \
  --source-class org.example.BallPanel \
  --target-class BallPhysics \
  --delegate ballPhysics \
  --members 'field:x,field:y,field:dx,field:dy,method:moveBall()'
```

Ergebnis:

```text
status: UNSAFE
```

Gründe: `moveBall()` ist öffentlich, wird extern genutzt und greift auf viele verbleibende Felder/Methoden sowie geerbte Swing-Methoden zu. Genau dafür soll das MVP konservativ abbrechen.
