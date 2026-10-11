#!/usr/bin/env python3
"""Generates ios/Photoliz.xcodeproj from the files under ios/Photoliz.

The project is plain and deterministic (stable object IDs), so it can be regenerated after adding
or removing source files without any third-party tool:

    python3 ios/tools/gen_xcodeproj.py

Sources: every *.swift and *.m under ios/Photoliz. Resources: Assets.xcassets and LaunchScreen.storyboard.
Info.plist, headers and the bridging header are referenced but not compiled or copied.
"""
import hashlib
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
IOS = os.path.dirname(HERE)
SRC_ROOT = os.path.join(IOS, 'Photoliz')
PROJ = os.path.join(IOS, 'Photoliz.xcodeproj')

TARGET = 'Photoliz'
BUNDLE_ID = 'dev.panasko.photoliz'
DEPLOYMENT = '13.0'
MARKETING_VERSION = '1.0.0'
BUILD_NUMBER = '1'

FILE_TYPES = {
    '.swift': 'sourcecode.swift',
    '.m': 'sourcecode.c.objc',
    '.h': 'sourcecode.c.h',
    '.plist': 'text.plist.xml',
    '.storyboard': 'file.storyboard',
    '.xcassets': 'folder.assetcatalog',
}
SKIP_DIRS = {'.DS_Store'}


def oid(*parts):
    """24 hex chars, stable for the same inputs."""
    return hashlib.sha1('/'.join(parts).encode()).hexdigest()[:24].upper()


def q(s):
    """Quote a pbxproj string when needed."""
    if s and all(c.isalnum() or c in '._/' for c in s) and not s.startswith('.') or s in ('',):
        return s if s else '""'
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'


class Group:
    def __init__(self, name, path):
        self.name = name
        self.path = path  # relative to parent group
        self.children = []  # (kind, obj)
        self.id = None


def scan():
    root = Group('Photoliz', 'Photoliz')
    files = []  # dicts: id, path (relative to ios/), name, type, role

    def walk(group, abs_dir, rel_dir):
        entries = sorted(os.listdir(abs_dir), key=lambda n: (not os.path.isdir(os.path.join(abs_dir, n)) or n.endswith('.xcassets'), n.lower()))
        for name in entries:
            if name in SKIP_DIRS or name.startswith('.'):
                continue
            full = os.path.join(abs_dir, name)
            rel = f'{rel_dir}/{name}'
            ext = os.path.splitext(name)[1]
            if os.path.isdir(full) and ext != '.xcassets':
                g = Group(name, name)
                g.id = oid('group', rel)
                group.children.append(('group', g))
                walk(g, full, rel)
                continue
            ftype = FILE_TYPES.get(ext)
            if ftype is None:
                continue
            if ext in ('.swift', '.m'):
                role = 'source'
            elif ext in ('.xcassets', '.storyboard'):
                role = 'resource'
            else:
                role = 'none'
            f = {'id': oid('file', rel), 'build': oid('build', rel), 'name': name, 'rel': rel, 'type': ftype, 'role': role}
            files.append(f)
            group.children.append(('file', f))

    root.id = oid('group', 'Photoliz')
    walk(root, SRC_ROOT, 'Photoliz')
    return root, files


def build_settings(d, indent):
    out = []
    pad = '\t' * indent
    for k in sorted(d):
        v = d[k]
        if isinstance(v, list):
            out.append(f'{pad}{k} = (')
            for item in v:
                out.append(f'{pad}\t{q(item)},')
            out.append(f'{pad});')
        else:
            out.append(f'{pad}{k} = {q(v)};')
    return '\n'.join(out)


def generate():
    root, files = scan()
    sources = [f for f in files if f['role'] == 'source']
    resources = [f for f in files if f['role'] == 'resource']
    if not sources:
        sys.exit('no sources found')

    ids = {
        'project': oid('project'),
        'main_group': oid('group', '<main>'),
        'products_group': oid('group', '<products>'),
        'product': oid('product', TARGET),
        'target': oid('target', TARGET),
        'sources_phase': oid('phase', 'sources'),
        'frameworks_phase': oid('phase', 'frameworks'),
        'resources_phase': oid('phase', 'resources'),
        'proj_configs': oid('configlist', 'project'),
        'target_configs': oid('configlist', 'target'),
        'proj_debug': oid('config', 'project', 'Debug'),
        'proj_release': oid('config', 'project', 'Release'),
        'target_debug': oid('config', 'target', 'Debug'),
        'target_release': oid('config', 'target', 'Release'),
    }

    common_project = {
        'ALWAYS_SEARCH_USER_PATHS': 'NO',
        'CLANG_ANALYZER_NONNULL': 'YES',
        'CLANG_ANALYZER_NUMBER_OBJECT_CONVERSION': 'YES_AGGRESSIVE',
        'CLANG_CXX_LANGUAGE_STANDARD': 'gnu++17',
        'CLANG_ENABLE_MODULES': 'YES',
        'CLANG_ENABLE_OBJC_ARC': 'YES',
        'CLANG_ENABLE_OBJC_WEAK': 'YES',
        'CLANG_WARN_BLOCK_CAPTURE_AUTORELEASING': 'YES',
        'CLANG_WARN_BOOL_CONVERSION': 'YES',
        'CLANG_WARN_COMMA': 'YES',
        'CLANG_WARN_CONSTANT_CONVERSION': 'YES',
        'CLANG_WARN_DEPRECATED_OBJC_IMPLEMENTATIONS': 'YES',
        'CLANG_WARN_DIRECT_OBJC_ISA_USAGE': 'YES_ERROR',
        'CLANG_WARN_DOCUMENTATION_COMMENTS': 'YES',
        'CLANG_WARN_EMPTY_BODY': 'YES',
        'CLANG_WARN_ENUM_CONVERSION': 'YES',
        'CLANG_WARN_INFINITE_RECURSION': 'YES',
        'CLANG_WARN_INT_CONVERSION': 'YES',
        'CLANG_WARN_NON_LITERAL_NULL_CONVERSION': 'YES',
        'CLANG_WARN_OBJC_IMPLICIT_RETAIN_SELF': 'YES',
        'CLANG_WARN_OBJC_LITERAL_CONVERSION': 'YES',
        'CLANG_WARN_OBJC_ROOT_CLASS': 'YES_ERROR',
        'CLANG_WARN_QUOTED_INCLUDE_IN_FRAMEWORK_HEADER': 'YES',
        'CLANG_WARN_RANGE_LOOP_ANALYSIS': 'YES',
        'CLANG_WARN_STRICT_PROTOTYPES': 'YES',
        'CLANG_WARN_SUSPICIOUS_MOVE': 'YES',
        'CLANG_WARN_UNGUARDED_AVAILABILITY': 'YES_AGGRESSIVE',
        'CLANG_WARN_UNREACHABLE_CODE': 'YES',
        'CLANG_WARN__DUPLICATE_METHOD_MATCH': 'YES',
        'COPY_PHASE_STRIP': 'NO',
        'ENABLE_STRICT_OBJC_MSGSEND': 'YES',
        'GCC_C_LANGUAGE_STANDARD': 'gnu11',
        'GCC_NO_COMMON_BLOCKS': 'YES',
        'GCC_WARN_64_TO_32_BIT_CONVERSION': 'YES',
        'GCC_WARN_ABOUT_RETURN_TYPE': 'YES_ERROR',
        'GCC_WARN_UNDECLARED_SELECTOR': 'YES',
        'GCC_WARN_UNINITIALIZED_AUTOS': 'YES_AGGRESSIVE',
        'GCC_WARN_UNUSED_FUNCTION': 'YES',
        'GCC_WARN_UNUSED_VARIABLE': 'YES',
        'IPHONEOS_DEPLOYMENT_TARGET': DEPLOYMENT,
        'MTL_FAST_MATH': 'YES',
        'SDKROOT': 'iphoneos',
    }
    proj_debug = dict(common_project, **{
        'DEBUG_INFORMATION_FORMAT': 'dwarf',
        'ENABLE_TESTABILITY': 'YES',
        'GCC_DYNAMIC_NO_PIC': 'NO',
        'GCC_OPTIMIZATION_LEVEL': '0',
        'GCC_PREPROCESSOR_DEFINITIONS': ['DEBUG=1', '$(inherited)'],
        'MTL_ENABLE_DEBUG_INFO': 'INCLUDE_SOURCE',
        'ONLY_ACTIVE_ARCH': 'YES',
        'SWIFT_ACTIVE_COMPILATION_CONDITIONS': 'DEBUG',
        'SWIFT_OPTIMIZATION_LEVEL': '-Onone',
    })
    proj_release = dict(common_project, **{
        'DEBUG_INFORMATION_FORMAT': 'dwarf-with-dsym',
        'ENABLE_NS_ASSERTIONS': 'NO',
        'MTL_ENABLE_DEBUG_INFO': 'NO',
        'SWIFT_COMPILATION_MODE': 'wholemodule',
        'SWIFT_OPTIMIZATION_LEVEL': '-O',
        'VALIDATE_PRODUCT': 'YES',
    })
    common_target = {
        'ASSETCATALOG_COMPILER_APPICON_NAME': 'AppIcon',
        'CODE_SIGN_STYLE': 'Automatic',
        'CURRENT_PROJECT_VERSION': BUILD_NUMBER,
        'DEVELOPMENT_TEAM': '',
        'ENABLE_BITCODE': 'NO',
        'GENERATE_INFOPLIST_FILE': 'NO',
        'INFOPLIST_FILE': 'Photoliz/Support/Info.plist',
        'IPHONEOS_DEPLOYMENT_TARGET': DEPLOYMENT,
        'LD_RUNPATH_SEARCH_PATHS': ['$(inherited)', '@executable_path/Frameworks'],
        'MARKETING_VERSION': MARKETING_VERSION,
        'PRODUCT_BUNDLE_IDENTIFIER': BUNDLE_ID,
        'PRODUCT_NAME': '$(TARGET_NAME)',
        'SUPPORTS_MACCATALYST': 'NO',
        'SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD': 'NO',
        'SUPPORTS_XR_DESIGNED_FOR_IPHONE_IPAD': 'NO',
        'SWIFT_EMIT_LOC_STRINGS': 'NO',
        'SWIFT_OBJC_BRIDGING_HEADER': 'Photoliz/Support/Photoliz-Bridging-Header.h',
        'SWIFT_VERSION': '5.0',
        'TARGETED_DEVICE_FAMILY': '1',
    }
    target_debug = dict(common_target)
    target_release = dict(common_target)

    L = []
    w = L.append
    w('// !$*UTF8*$!')
    w('{')
    w('\tarchiveVersion = 1;')
    w('\tclasses = {')
    w('\t};')
    w('\tobjectVersion = 56;')
    w('\tobjects = {')
    w('')

    w('/* Begin PBXBuildFile section */')
    for f in sources + resources:
        phase = 'Sources' if f['role'] == 'source' else 'Resources'
        w(f"\t\t{f['build']} /* {f['name']} in {phase} */ = {{isa = PBXBuildFile; fileRef = {f['id']} /* {f['name']} */; }};")
    w('/* End PBXBuildFile section */')
    w('')

    w('/* Begin PBXFileReference section */')
    w(f"\t\t{ids['product']} /* {TARGET}.app */ = {{isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = {TARGET}.app; sourceTree = BUILT_PRODUCTS_DIR; }};")
    for f in sorted(files, key=lambda f: f['id']):
        w(f"\t\t{f['id']} /* {f['name']} */ = {{isa = PBXFileReference; lastKnownFileType = {f['type']}; path = {q(f['name'])}; sourceTree = \"<group>\"; }};")
    w('/* End PBXFileReference section */')
    w('')

    w('/* Begin PBXFrameworksBuildPhase section */')
    w(f"\t\t{ids['frameworks_phase']} /* Frameworks */ = {{")
    w('\t\t\tisa = PBXFrameworksBuildPhase;')
    w('\t\t\tbuildActionMask = 2147483647;')
    w('\t\t\tfiles = (')
    w('\t\t\t);')
    w('\t\t\trunOnlyForDeploymentPostprocessing = 0;')
    w('\t\t};')
    w('/* End PBXFrameworksBuildPhase section */')
    w('')

    w('/* Begin PBXGroup section */')
    w(f"\t\t{ids['main_group']} = {{")
    w('\t\t\tisa = PBXGroup;')
    w('\t\t\tchildren = (')
    w(f"\t\t\t\t{root.id} /* {root.name} */,")
    w(f"\t\t\t\t{ids['products_group']} /* Products */,")
    w('\t\t\t);')
    w('\t\t\tsourceTree = "<group>";')
    w('\t\t};')
    w(f"\t\t{ids['products_group']} /* Products */ = {{")
    w('\t\t\tisa = PBXGroup;')
    w('\t\t\tchildren = (')
    w(f"\t\t\t\t{ids['product']} /* {TARGET}.app */,")
    w('\t\t\t);')
    w('\t\t\tname = Products;')
    w('\t\t\tsourceTree = "<group>";')
    w('\t\t};')

    def emit_group(g):
        w(f"\t\t{g.id} /* {g.name} */ = {{")
        w('\t\t\tisa = PBXGroup;')
        w('\t\t\tchildren = (')
        for kind, c in g.children:
            if kind == 'group':
                w(f"\t\t\t\t{c.id} /* {c.name} */,")
            else:
                w(f"\t\t\t\t{c['id']} /* {c['name']} */,")
        w('\t\t\t);')
        w(f'\t\t\tpath = {q(g.path)};')
        w('\t\t\tsourceTree = "<group>";')
        w('\t\t};')
        for kind, c in g.children:
            if kind == 'group':
                emit_group(c)

    emit_group(root)
    w('/* End PBXGroup section */')
    w('')

    w('/* Begin PBXNativeTarget section */')
    w(f"\t\t{ids['target']} /* {TARGET} */ = {{")
    w('\t\t\tisa = PBXNativeTarget;')
    w(f"\t\t\tbuildConfigurationList = {ids['target_configs']} /* Build configuration list for PBXNativeTarget \"{TARGET}\" */;")
    w('\t\t\tbuildPhases = (')
    w(f"\t\t\t\t{ids['sources_phase']} /* Sources */,")
    w(f"\t\t\t\t{ids['frameworks_phase']} /* Frameworks */,")
    w(f"\t\t\t\t{ids['resources_phase']} /* Resources */,")
    w('\t\t\t);')
    w('\t\t\tbuildRules = (')
    w('\t\t\t);')
    w('\t\t\tdependencies = (')
    w('\t\t\t);')
    w(f'\t\t\tname = {TARGET};')
    w(f'\t\t\tproductName = {TARGET};')
    w(f"\t\t\tproductReference = {ids['product']} /* {TARGET}.app */;")
    w('\t\t\tproductType = "com.apple.product-type.application";')
    w('\t\t};')
    w('/* End PBXNativeTarget section */')
    w('')

    w('/* Begin PBXProject section */')
    w(f"\t\t{ids['project']} /* Project object */ = {{")
    w('\t\t\tisa = PBXProject;')
    w('\t\t\tattributes = {')
    w('\t\t\t\tBuildIndependentTargetsInParallel = 1;')
    w('\t\t\t\tLastSwiftUpdateCheck = 1640;')
    w('\t\t\t\tLastUpgradeCheck = 1640;')
    w('\t\t\t\tTargetAttributes = {')
    w(f"\t\t\t\t\t{ids['target']} = {{")
    w('\t\t\t\t\t\tCreatedOnToolsVersion = 16.4;')
    w('\t\t\t\t\t};')
    w('\t\t\t\t};')
    w('\t\t\t};')
    w(f"\t\t\tbuildConfigurationList = {ids['proj_configs']} /* Build configuration list for PBXProject \"{TARGET}\" */;")
    w('\t\t\tcompatibilityVersion = "Xcode 14.0";')
    w('\t\t\tdevelopmentRegion = en;')
    w('\t\t\thasScannedForEncodings = 0;')
    w('\t\t\tknownRegions = (')
    w('\t\t\t\ten,')
    w('\t\t\t\tBase,')
    w('\t\t\t);')
    w(f"\t\t\tmainGroup = {ids['main_group']};")
    w(f"\t\t\tproductRefGroup = {ids['products_group']} /* Products */;")
    w('\t\t\tprojectDirPath = "";')
    w('\t\t\tprojectRoot = "";')
    w('\t\t\ttargets = (')
    w(f"\t\t\t\t{ids['target']} /* {TARGET} */,")
    w('\t\t\t);')
    w('\t\t};')
    w('/* End PBXProject section */')
    w('')

    w('/* Begin PBXResourcesBuildPhase section */')
    w(f"\t\t{ids['resources_phase']} /* Resources */ = {{")
    w('\t\t\tisa = PBXResourcesBuildPhase;')
    w('\t\t\tbuildActionMask = 2147483647;')
    w('\t\t\tfiles = (')
    for f in resources:
        w(f"\t\t\t\t{f['build']} /* {f['name']} in Resources */,")
    w('\t\t\t);')
    w('\t\t\trunOnlyForDeploymentPostprocessing = 0;')
    w('\t\t};')
    w('/* End PBXResourcesBuildPhase section */')
    w('')

    w('/* Begin PBXSourcesBuildPhase section */')
    w(f"\t\t{ids['sources_phase']} /* Sources */ = {{")
    w('\t\t\tisa = PBXSourcesBuildPhase;')
    w('\t\t\tbuildActionMask = 2147483647;')
    w('\t\t\tfiles = (')
    for f in sources:
        w(f"\t\t\t\t{f['build']} /* {f['name']} in Sources */,")
    w('\t\t\t);')
    w('\t\t\trunOnlyForDeploymentPostprocessing = 0;')
    w('\t\t};')
    w('/* End PBXSourcesBuildPhase section */')
    w('')

    w('/* Begin XCBuildConfiguration section */')
    for key, name, settings in (
        ('proj_debug', 'Debug', proj_debug),
        ('proj_release', 'Release', proj_release),
        ('target_debug', 'Debug', target_debug),
        ('target_release', 'Release', target_release),
    ):
        w(f"\t\t{ids[key]} /* {name} */ = {{")
        w('\t\t\tisa = XCBuildConfiguration;')
        w('\t\t\tbuildSettings = {')
        w(build_settings(settings, 4))
        w('\t\t\t};')
        w(f'\t\t\tname = {name};')
        w('\t\t};')
    w('/* End XCBuildConfiguration section */')
    w('')

    w('/* Begin XCConfigurationList section */')
    for key, owner, debug, release in (
        ('proj_configs', f'PBXProject "{TARGET}"', 'proj_debug', 'proj_release'),
        ('target_configs', f'PBXNativeTarget "{TARGET}"', 'target_debug', 'target_release'),
    ):
        w(f"\t\t{ids[key]} /* Build configuration list for {owner} */ = {{")
        w('\t\t\tisa = XCConfigurationList;')
        w('\t\t\tbuildConfigurations = (')
        w(f"\t\t\t\t{ids[debug]} /* Debug */,")
        w(f"\t\t\t\t{ids[release]} /* Release */,")
        w('\t\t\t);')
        w('\t\t\tdefaultConfigurationIsVisible = 0;')
        w('\t\t\tdefaultConfigurationName = Release;')
        w('\t\t};')
    w('/* End XCConfigurationList section */')
    w('\t};')
    w(f"\trootObject = {ids['project']} /* Project object */;")
    w('}')
    pbx = '\n'.join(L) + '\n'

    scheme = f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme
   LastUpgradeVersion = "1640"
   version = "1.7">
   <BuildAction
      parallelizeBuildables = "YES"
      buildImplicitDependencies = "YES">
      <BuildActionEntries>
         <BuildActionEntry
            buildForTesting = "YES"
            buildForRunning = "YES"
            buildForProfiling = "YES"
            buildForArchiving = "YES"
            buildForAnalyzing = "YES">
            <BuildableReference
               BuildableIdentifier = "primary"
               BlueprintIdentifier = "{ids['target']}"
               BuildableName = "{TARGET}.app"
               BlueprintName = "{TARGET}"
               ReferencedContainer = "container:{TARGET}.xcodeproj">
            </BuildableReference>
         </BuildActionEntry>
      </BuildActionEntries>
   </BuildAction>
   <TestAction
      buildConfiguration = "Debug"
      selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
      selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"
      shouldUseLaunchSchemeArgsEnv = "YES">
   </TestAction>
   <LaunchAction
      buildConfiguration = "Debug"
      selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
      selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"
      launchStyle = "0"
      useCustomWorkingDirectory = "NO"
      ignoresPersistentStateOnLaunch = "NO"
      debugDocumentVersioning = "YES"
      debugServiceExtension = "internal"
      allowLocationSimulation = "YES">
      <BuildableProductRunnable
         runnableDebuggingMode = "0">
         <BuildableReference
            BuildableIdentifier = "primary"
            BlueprintIdentifier = "{ids['target']}"
            BuildableName = "{TARGET}.app"
            BlueprintName = "{TARGET}"
            ReferencedContainer = "container:{TARGET}.xcodeproj">
         </BuildableReference>
      </BuildableProductRunnable>
   </LaunchAction>
   <ProfileAction
      buildConfiguration = "Release"
      shouldUseLaunchSchemeArgsEnv = "YES"
      savedToolIdentifier = ""
      useCustomWorkingDirectory = "NO"
      debugDocumentVersioning = "YES">
      <BuildableProductRunnable
         runnableDebuggingMode = "0">
         <BuildableReference
            BuildableIdentifier = "primary"
            BlueprintIdentifier = "{ids['target']}"
            BuildableName = "{TARGET}.app"
            BlueprintName = "{TARGET}"
            ReferencedContainer = "container:{TARGET}.xcodeproj">
         </BuildableReference>
      </BuildableProductRunnable>
   </ProfileAction>
   <AnalyzeAction
      buildConfiguration = "Debug">
   </AnalyzeAction>
   <ArchiveAction
      buildConfiguration = "Release"
      revealArchiveInOrganizer = "YES">
   </ArchiveAction>
</Scheme>
'''
    workspace = '''<?xml version="1.0" encoding="UTF-8"?>
<Workspace
   version = "1.0">
   <FileRef
      location = "self:">
   </FileRef>
</Workspace>
'''
    os.makedirs(os.path.join(PROJ, 'xcshareddata', 'xcschemes'), exist_ok=True)
    os.makedirs(os.path.join(PROJ, 'project.xcworkspace', 'xcshareddata'), exist_ok=True)
    with open(os.path.join(PROJ, 'project.pbxproj'), 'w') as fh:
        fh.write(pbx)
    with open(os.path.join(PROJ, 'xcshareddata', 'xcschemes', f'{TARGET}.xcscheme'), 'w') as fh:
        fh.write(scheme)
    with open(os.path.join(PROJ, 'project.xcworkspace', 'contents.xcworkspacedata'), 'w') as fh:
        fh.write(workspace)
    # Keeps Xcode from offering to "migrate" to a workspace build system setting on old versions.
    with open(os.path.join(PROJ, 'project.xcworkspace', 'xcshareddata', 'IDEWorkspaceChecks.plist'), 'w') as fh:
        fh.write('''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>IDEDidComputeMac32BitWarning</key>
	<true/>
</dict>
</plist>
''')
    print(f'{len(sources)} sources, {len(resources)} resources -> {os.path.relpath(PROJ, os.getcwd())}')


if __name__ == '__main__':
    generate()
