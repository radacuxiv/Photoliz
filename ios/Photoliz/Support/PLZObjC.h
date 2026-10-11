#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// AVFoundation reports invalid capture settings by raising Objective-C exceptions, which Swift cannot catch.
/// Every risky call goes through this so a camera quirk becomes an error message instead of a crash.
@interface PLZObjC : NSObject

+ (BOOL)catchException:(void (NS_NOESCAPE ^)(void))tryBlock error:(NSError * _Nullable * _Nullable)error;

@end

NS_ASSUME_NONNULL_END
