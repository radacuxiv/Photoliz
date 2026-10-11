#import "PLZObjC.h"

@implementation PLZObjC

+ (BOOL)catchException:(void (NS_NOESCAPE ^)(void))tryBlock error:(NSError * _Nullable * _Nullable)error {
    @try {
        tryBlock();
        return YES;
    } @catch (NSException *exception) {
        if (error != NULL) {
            NSString *reason = exception.reason ?: exception.name;
            *error = [NSError errorWithDomain:@"dev.panasko.photoliz.objc"
                                         code:1
                                     userInfo:@{ NSLocalizedDescriptionKey: reason ?: @"Objective-C exception" }];
        }
        return NO;
    }
}

@end
