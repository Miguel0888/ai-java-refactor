# ai-java-refactor

Kleines Java-8-kompatibles CLI-JAR für agententaugliche Java-Refactorings mit **Lazy Symbol Solving**.

Das Tool baut keinen dauerhaften IDE-Index auf. Jede Operation erzeugt nur den minimal nötigen Kontext:

```text
Operation Request
→ Cheap token search
→ Parse only candidate files
→ Resolve only relevant AST nodes
→ Build a small operation-local usage graph
→ Plan source-preserving edits
→ Apply only if the plan is SAFE
```

## Enthaltene MVP-Funktionen

- `outline`: Grobe AST-Übersicht über Dateien, Klassen, Felder und Methoden.
- `rename-plan` / `rename-apply`: Rename für Felder und Methoden anhand einer Symbol-ID.
- `extract-class-plan` / `extract-class-apply`: konservatives Extract Class für private Felder und private Methoden.

## Build

```bash
./build.sh
```

Der Build verwendet Gradle und lädt JavaParser aus Maven Central. Fremde JARs werden nicht versioniert.

Die relevanten Dependencies stehen in `build.gradle`:

```groovy
dependencies {
    implementation 'com.github.javaparser:javaparser-core:3.26.4'
    implementation 'com.github.javaparser:javaparser-symbol-solver-core:3.26.4'
}
```

## Ausführen

```bash
dist/ai-java-refactor outline --project /path/to/project
```

### Rename planen

```bash
dist/ai-java-refactor rename-plan \
  --project /path/to/project \
  --symbol 'org.example.BallPanel#moveBall()' \
  --new-name updateBallPosition
```

### Rename anwenden

```bash
dist/ai-java-refactor rename-apply \
  --project /path/to/project \
  --symbol 'org.example.BallPanel#moveBall()' \
  --new-name updateBallPosition
```

### Extract Class planen

```bash
dist/ai-java-refactor extract-class-plan \
  --project /path/to/project \
  --source-class org.example.Sample \
  --target-class SampleState \
  --delegate sampleState \
  --members 'field:x,field:y,method:inc()'
```

### Extract Class anwenden

```bash
dist/ai-java-refactor extract-class-apply \
  --project /path/to/project \
  --source-class org.example.Sample \
  --target-class SampleState \
  --delegate sampleState \
  --members 'field:x,field:y'
```

## Sicherheitsmodell

`apply` läuft nur bei `SAFE`. Bei `UNSAFE` wird nicht geschrieben.

Typische Gründe für `UNSAFE`:

- externe Nutzung eines zu verschiebenden Members,
- ausgewählte Methode greift auf verbleibende Felder/Methoden zu,
- ausgewählte Methode nutzt `this`, `super` oder geerbte Instanzmethoden,
- nicht unterstützte Schreibzugriffe oder überlappende Edits.

## MVP-Grenzen

Der Resolver ist bewusst leichtgewichtig und AST-basiert. Er ersetzt noch keinen vollständigen Java-Compiler oder IntelliJ-PSI.

Unterstützt werden im MVP vor allem diese Fälle:

- Methoden-/Feld-Rename innerhalb normaler Java-Quellen,
- einfache Feld-/Methodenreferenzen,
- Extract Class mit privaten Feldern und privaten Methoden,
- Getter-/Setter-Erzeugung für gelesene/geschriebene verschobene Felder.

Noch bewusst konservativ:

- Overload-/Override-Hierarchien,
- externe API-Kompatibilität,
- komplexe Datenflussfälle,
- Reflection, Lombok und Annotation-Processor-Magie,
- perfektes Pretty Printing.

## Hinweise

`javaparser-core` und `javaparser-symbol-solver-core` sind als Gradle/Maven-Central-Dependencies eingebunden. Der aktuelle MVP verwendet primär `javaparser-core` plus einen eigenen leichten Resolver; der Symbol Solver bleibt als austauschbare Resolver-Grundlage vorgesehen.
